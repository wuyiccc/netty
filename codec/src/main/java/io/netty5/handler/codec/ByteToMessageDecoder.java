/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty5.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufConvertible;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty5.channel.ChannelConfig;
import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerAdapter;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.ChannelPipeline;
import io.netty5.channel.ChannelShutdownDirection;
import io.netty5.channel.internal.DelegatingChannelHandlerContext;
import io.netty5.util.internal.StringUtil;

import static io.netty5.util.internal.ObjectUtil.checkPositive;
import static java.lang.Integer.MAX_VALUE;
import static java.util.Objects.requireNonNull;

/**
 * {@link ChannelHandler} which decodes bytes in a stream-like fashion from one {@link ByteBuf} to an
 * other Message type.
 *
 * For example here is an implementation which reads all readable bytes from
 * the input {@link ByteBuf}, creates a new {@link ByteBuf} and forward it to the next {@link ChannelHandler}
 * in the {@link ChannelPipeline}.
 *
 * <pre>
 *     public class SquareDecoder extends {@link ByteToMessageDecoder} {
 *         {@code @Override}
 *         public void decode({@link ChannelHandlerContext} ctx, {@link ByteBuf} in)
 *                 throws {@link Exception} {
 *             ctx.fireChannelRead(in.readBytes(in.readableBytes()));
 *         }
 *     }
 * </pre>
 *
 * <h3>Frame detection</h3>
 * <p>
 * Generally frame detection should be handled earlier in the pipeline by adding a
 * {@link DelimiterBasedFrameDecoder}, {@link FixedLengthFrameDecoder}, {@link LengthFieldBasedFrameDecoder},
 * or {@link LineBasedFrameDecoder}.
 * <p>
 * If a custom frame decoder is required, then one needs to be careful when implementing
 * one with {@link ByteToMessageDecoder}. Ensure there are enough bytes in the buffer for a
 * complete frame by checking {@link ByteBuf#readableBytes()}. If there are not enough bytes
 * for a complete frame, return without modifying the reader index to allow more bytes to arrive.
 * <p>
 * To check for complete frames without modifying the reader index, use methods like {@link ByteBuf#getInt(int)}.
 * One <strong>MUST</strong> use the reader index when using methods like {@link ByteBuf#getInt(int)}.
 * For example calling <tt>in.getInt(0)</tt> is assuming the frame starts at the beginning of the buffer, which
 * is not always the case. Use <tt>in.getInt(in.readerIndex())</tt> instead.
 * <h3>Pitfalls</h3>
 * <p>
 * Be aware that sub-classes of {@link ByteToMessageDecoder} <strong>MUST NOT</strong>
 * annotated with {@link @Sharable}.
 * <p>
 * Some methods such as {@link ByteBuf#readBytes(int)} will cause a memory leak if the returned buffer
 * is not released or fired through the {@link ChannelPipeline} via
 * {@link ChannelHandlerContext#fireChannelRead(Object)}. Use derived buffers like {@link ByteBuf#readSlice(int)} to
 * avoid leaking memory.
 */
public abstract class ByteToMessageDecoder extends ChannelHandlerAdapter {

    /**
     * Cumulate {@link ByteBuf}s by merge them into one {@link ByteBuf}'s, using memory copies.
     */
    public static final Cumulator MERGE_CUMULATOR = (alloc, cumulation, in) -> {
        if (!cumulation.isReadable() && in.isContiguous()) {
            // If cumulation is empty and input buffer is contiguous, use it directly
            cumulation.release();
            return in;
        }
        try {
            final int required = in.readableBytes();
            if (required > cumulation.maxWritableBytes() ||
                    required > cumulation.maxFastWritableBytes() && cumulation.refCnt() > 1 ||
                    cumulation.isReadOnly()) {
                // Expand cumulation (by replacing it) under the following conditions:
                // - cumulation cannot be resized to accommodate the additional data
                // - cumulation can be expanded with a reallocation operation to accommodate but the buffer is
                //   assumed to be shared (e.g. refCnt() > 1) and the reallocation may not be safe.
                return expandCumulation(alloc, cumulation, in);
            }
            cumulation.writeBytes(in, in.readerIndex(), required);
            in.readerIndex(in.writerIndex());
            return cumulation;
        } finally {
            // We must release in  all cases as otherwise it may produce a leak if writeBytes(...) throw
            // for whatever release (for example because of OutOfMemoryError)
            in.release();
        }
    };

    /**
     * Cumulate {@link ByteBuf}s by add them to a {@link CompositeByteBuf} and so do no memory copy whenever possible.
     * Be aware that {@link CompositeByteBuf} use a more complex indexing implementation so depending on your use-case
     * and the decoder implementation this may be slower than just use the {@link #MERGE_CUMULATOR}.
     */

    public static final Cumulator COMPOSITE_CUMULATOR = (alloc, cumulation, in) -> {
        if (!cumulation.isReadable()) {
            cumulation.release();
            return in;
        }
        CompositeByteBuf composite = null;
        try {
            if (cumulation instanceof CompositeByteBuf && cumulation.refCnt() == 1) {
                composite = (CompositeByteBuf) cumulation;
                // Writer index must equal capacity if we are going to "write"
                // new components to the end
                if (composite.writerIndex() != composite.capacity()) {
                    composite.capacity(composite.writerIndex());
                }
            } else {
                composite = alloc.compositeBuffer(MAX_VALUE).addFlattenedComponents(true, cumulation);
            }
            composite.addFlattenedComponents(true, in);
            in = null;
            return composite;
        } finally {
            if (in != null) {
                // We must release if the ownership was not transferred as otherwise it may produce a leak
                in.release();
                // Also release any new buffer allocated if we're not returning it
                if (composite != null && composite != cumulation) {
                    composite.release();
                }
            }
        }
    };

    private ByteBuf cumulation;
    private Cumulator cumulator = MERGE_CUMULATOR;
    private boolean singleDecode;
    private boolean first;

    /**
     * This flag is used to determine if we need to call {@link ChannelHandlerContext#read()} to consume more data
     * when {@link ChannelConfig#isAutoRead()} is {@code false}.
     */
    private boolean firedChannelRead;

    private boolean selfFiredChannelRead;

    private int discardAfterReads = 16;
    private int numReads;
    private ByteToMessageDecoderContext context;

    @Override
    public final boolean isSharable() {
        // Can't be sharable as we keep state.
        return false;
    }

    /**
     * If set then only one message is decoded on each {@link #channelRead(ChannelHandlerContext, Object)}
     * call. This may be useful if you need to do some protocol upgrade and want to make sure nothing is mixed up.
     *
     * Default is {@code false} as this has performance impacts.
     */
    public void setSingleDecode(boolean singleDecode) {
        this.singleDecode = singleDecode;
    }

    /**
     * If {@code true} then only one message is decoded on each
     * {@link #channelRead(ChannelHandlerContext, Object)} call.
     *
     * Default is {@code false} as this has performance impacts.
     */
    public boolean isSingleDecode() {
        return singleDecode;
    }

    /**
     * Set the {@link Cumulator} to use for cumulate the received {@link ByteBuf}s.
     */
    public void setCumulator(Cumulator cumulator) {
        requireNonNull(cumulator, "cumulator");
        this.cumulator = cumulator;
    }

    /**
     * Set the number of reads after which {@link ByteBuf#discardSomeReadBytes()} are called and so free up memory.
     * The default is {@code 16}.
     */
    public void setDiscardAfterReads(int discardAfterReads) {
        checkPositive(discardAfterReads, "discardAfterReads");
        this.discardAfterReads = discardAfterReads;
    }

    /**
     * Returns the actual number of readable bytes in the internal cumulative
     * buffer of this decoder. You usually do not need to rely on this value
     * to write a decoder. Use it only when you must use it at your own risk.
     * This method is a shortcut to {@link #internalBuffer() internalBuffer().readableBytes()}.
     */
    protected int actualReadableBytes() {
        return internalBuffer().readableBytes();
    }

    /**
     * Returns the internal cumulative buffer of this decoder. You usually
     * do not need to access the internal buffer directly to write a decoder.
     * Use it only when you must use it at your own risk.
     */
    protected ByteBuf internalBuffer() {
        if (cumulation != null) {
            return cumulation;
        } else {
            return Unpooled.EMPTY_BUFFER;
        }
    }

    @Override
    public final void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        context = new ByteToMessageDecoderContext(ctx);
        handlerAdded0(context);
    }

    protected void handlerAdded0(ChannelHandlerContext ctx) throws Exception {
    }

    @Override
    public final void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        ByteBuf buf = cumulation;
        if (buf != null) {
            // Directly set this to null, so we are sure we not access it in any other method here anymore.
            cumulation = null;
            numReads = 0;
            int readable = buf.readableBytes();
            if (readable > 0) {
                ctx.fireChannelRead(buf);
                ctx.fireChannelReadComplete();
            } else {
                buf.release();
            }
        }
        handlerRemoved0(context);
    }

    /**
     * Gets called after the {@link ByteToMessageDecoder} was removed from the actual context and it doesn't handle
     * events anymore.
     */
    protected void handlerRemoved0(ChannelHandlerContext ctx) throws Exception { }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBufConvertible) {
            selfFiredChannelRead = true;

            try {
                ByteBuf data = ((ByteBufConvertible) msg).asByteBuf();
                first = cumulation == null;
                if (first) {
                    cumulation = data;
                } else {
                    cumulation = cumulator.cumulate(ctx.alloc(), cumulation, data);
                }
                assert context.delegatingCtx() == ctx || ctx == context;

                callDecode(context, cumulation);
            } catch (DecoderException e) {
                throw e;
            } catch (Exception e) {
                throw new DecoderException(e);
            } finally {
                if (cumulation != null && !cumulation.isReadable()) {
                    numReads = 0;
                    cumulation.release();
                    cumulation = null;
                } else if (++ numReads >= discardAfterReads) {
                    // We did enough reads already try to discard some bytes, so we not risk to see a OOME.
                    // See https://github.com/netty/netty/issues/4275
                    numReads = 0;
                    discardSomeReadBytes();
                }

                firedChannelRead |= context.fireChannelReadCallCount() > 0;
                context.reset();
            }
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        numReads = 0;
        discardSomeReadBytes();
        if (selfFiredChannelRead && !firedChannelRead && !ctx.channel().config().isAutoRead()) {
            ctx.read();
        }
        firedChannelRead = false;
        ctx.fireChannelReadComplete();
    }

    protected final void discardSomeReadBytes() {
        if (cumulation != null && !first && cumulation.refCnt() == 1) {
            // discard some bytes if possible to make more room in the
            // buffer but only if the refCnt == 1  as otherwise the user may have
            // used slice().retain() or duplicate().retain().
            //
            // See:
            // - https://github.com/netty/netty/issues/2327
            // - https://github.com/netty/netty/issues/1764
            cumulation.discardSomeReadBytes();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        assert context.delegatingCtx() == ctx || ctx == context;
        channelInputClosed(context, true);
    }

    @Override
    public void channelShutdown(ChannelHandlerContext ctx, ChannelShutdownDirection direction) throws Exception {
        ctx.fireChannelShutdown(direction);
        if (direction == ChannelShutdownDirection.Inbound) {
            // The decodeLast method is invoked when a channelInactive event is encountered.
            // This method is responsible for ending requests in some situations and must be called
            // when the input has been shutdown.
            assert context.delegatingCtx() == ctx || ctx == context;
            channelInputClosed(context, false);
        }
    }

    private void channelInputClosed(ByteToMessageDecoderContext ctx, boolean callChannelInactive) {
        try {
            channelInputClosed(ctx);
        } catch (DecoderException e) {
            throw e;
        } catch (Exception e) {
            throw new DecoderException(e);
        } finally {
            if (cumulation != null) {
                cumulation.release();
                cumulation = null;
            }
            if (ctx.fireChannelReadCallCount() > 0) {
                ctx.reset();
                // Something was read, call fireChannelReadComplete()
                ctx.fireChannelReadComplete();
            }
            if (callChannelInactive) {
                ctx.fireChannelInactive();
            }
        }
    }

    /**
     * Called when the input of the channel was closed which may be because it changed to inactive or because of
     * shutdown.
     */
    private void channelInputClosed(ByteToMessageDecoderContext ctx) throws Exception {
        if (cumulation != null) {
            callDecode(ctx, cumulation);
            // If callDecode(...) removed the handle from the pipeline we should not call decodeLast(...) as this would
            // be unexpected.
            if (!ctx.isRemoved()) {
                // Use Unpooled.EMPTY_BUFFER if cumulation become null after calling callDecode(...).
                // See https://github.com/netty/netty/issues/10802.
                ByteBuf buffer = cumulation == null ? Unpooled.EMPTY_BUFFER : cumulation;
                decodeLast(ctx, buffer);
            }
        } else {
            decodeLast(ctx, Unpooled.EMPTY_BUFFER);
        }
    }

    /**
     * Called once data should be decoded from the given {@link ByteBuf}. This method will call
     * {@link #decode(ChannelHandlerContext, ByteBuf)} as long as decoding should take place.
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in            the {@link ByteBuf} from which to read data
     */
    private void callDecode(ByteToMessageDecoderContext ctx, ByteBuf in) {
        try {
            while (in.isReadable() && !ctx.isRemoved()) {

                int oldInputLength = in.readableBytes();
                int numReadCalled = ctx.fireChannelReadCallCount();
                decodeRemovalReentryProtection(ctx, in);

                // Check if this handler was removed before continuing the loop.
                // If it was removed, it is not safe to continue to operate on the buffer.
                //
                // See https://github.com/netty/netty/issues/1664
                if (ctx.isRemoved()) {
                    break;
                }

                if (numReadCalled == ctx.fireChannelReadCallCount()) {
                    if (oldInputLength == in.readableBytes()) {
                        break;
                    } else {
                        continue;
                    }
                }

                if (oldInputLength == in.readableBytes()) {
                    throw new DecoderException(
                            StringUtil.simpleClassName(getClass()) +
                                    ".decode() did not read anything but decoded a message.");
                }

                if (isSingleDecode()) {
                    break;
                }
            }
        } catch (DecoderException e) {
            throw e;
        } catch (Exception cause) {
            throw new DecoderException(cause);
        }
    }

    /**
     * Decode the from one {@link ByteBuf} to an other. This method will be called till either the input
     * {@link ByteBuf} has nothing to read when return from this method or till nothing was read from the input
     * {@link ByteBuf}.
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in            the {@link ByteBuf} from which to read data
     * @throws Exception    is thrown if an error occurs
     */
    protected abstract void decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception;

    /**
     * Decode the from one {@link ByteBuf} to an other. This method will be called till either the input
     * {@link ByteBuf} has nothing to read when return from this method or till nothing was read from the input
     * {@link ByteBuf}.
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in            the {@link ByteBuf} from which to read data
     * @throws Exception    is thrown if an error occurs
     */
    private void decodeRemovalReentryProtection(ChannelHandlerContext ctx, ByteBuf in)
            throws Exception {
        decode(ctx, in);
    }

    /**
     * Is called one last time when the {@link ChannelHandlerContext} goes in-active. Which means the
     * {@link #channelInactive(ChannelHandlerContext)} was triggered.
     *
     * By default, this will just call {@link #decode(ChannelHandlerContext, ByteBuf)} but sub-classes may
     * override this for some special cleanup operation.
     */
    protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        if (in.isReadable()) {
            // Only call decode() if there is something left in the buffer to decode.
            // See https://github.com/netty/netty/issues/4386
            decodeRemovalReentryProtection(ctx, in);
        }
    }

    private static ByteBuf expandCumulation(ByteBufAllocator alloc, ByteBuf oldCumulation, ByteBuf in) {
        ByteBuf newCumulation = alloc.buffer(alloc.calculateNewCapacity(
                oldCumulation.readableBytes() + in.readableBytes(), MAX_VALUE));
        ByteBuf toRelease = newCumulation;
        try {
            newCumulation.writeBytes(oldCumulation);
            newCumulation.writeBytes(in);
            toRelease = oldCumulation;
            return newCumulation;
        } finally {
            toRelease.release();
        }
    }

    /**
     * Cumulate {@link ByteBuf}s.
     */
    public interface Cumulator {
        /**
         * Cumulate the given {@link ByteBuf}s and return the {@link ByteBuf} that holds the cumulated bytes.
         * The implementation is responsible to correctly handle the life-cycle of the given {@link ByteBuf}s and so
         * call {@link ByteBuf#release()} if a {@link ByteBuf} is fully consumed.
         */
        ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in);
    }

    private static final class ByteToMessageDecoderContext extends DelegatingChannelHandlerContext {
        private int fireChannelReadCalled;

        private ByteToMessageDecoderContext(ChannelHandlerContext ctx) {
            super(ctx);
        }

        void reset() {
            fireChannelReadCalled = 0;
        }

        int fireChannelReadCallCount() {
            return fireChannelReadCalled;
        }

        @Override
        public ChannelHandlerContext fireChannelRead(Object msg) {
            fireChannelReadCalled ++;
            super.fireChannelRead(msg);
            return this;
        }
    }
}
