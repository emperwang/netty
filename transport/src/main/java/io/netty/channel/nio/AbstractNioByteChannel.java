/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.nio;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.FileRegion;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.internal.ChannelUtils;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.util.internal.StringUtil;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;

import static io.netty.channel.internal.ChannelUtils.WRITE_STATUS_SNDBUF_FULL;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on bytes.
 */
public abstract class AbstractNioByteChannel extends AbstractNioChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);
    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(ByteBuf.class) + ", " +
            StringUtil.simpleClassName(FileRegion.class) + ')';

    private final Runnable flushTask = new Runnable() {
        @Override
        public void run() {
            // Calling flush0 directly to ensure we not try to flush messages that were added via write(...) in the
            // meantime.
            ((AbstractNioUnsafe) unsafe()).flush0();
        }
    };
    private boolean inputClosedSeenErrorOnRead;

    /**
     * Create a new instance
     *
     * @param parent            the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param ch                the underlying {@link SelectableChannel} on which it operates
     */
    protected AbstractNioByteChannel(Channel parent, SelectableChannel ch) {
        super(parent, ch, SelectionKey.OP_READ);
    }

    /**
     * Shutdown the input side of the channel.
     */
    protected abstract ChannelFuture shutdownInput();

    protected boolean isInputShutdown0() {
        return false;
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioByteUnsafe();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    final boolean shouldBreakReadReady(ChannelConfig config) {
        return isInputShutdown0() && (inputClosedSeenErrorOnRead || !isAllowHalfClosure(config));
    }

    private static boolean isAllowHalfClosure(ChannelConfig config) {
        return config instanceof SocketChannelConfig &&
                ((SocketChannelConfig) config).isAllowHalfClosure();
    }

    protected class NioByteUnsafe extends AbstractNioUnsafe {

        private void closeOnRead(ChannelPipeline pipeline) {
            if (!isInputShutdown0()) {
                if (isAllowHalfClosure(config())) {
                    shutdownInput();
                    pipeline.fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
                } else {
                    close(voidPromise());
                }
            } else {
                inputClosedSeenErrorOnRead = true;
                pipeline.fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);
            }
        }

        private void handleReadException(ChannelPipeline pipeline, ByteBuf byteBuf, Throwable cause, boolean close,
                RecvByteBufAllocator.Handle allocHandle) {
            if (byteBuf != null) {
                if (byteBuf.isReadable()) {
                    readPending = false;
                    pipeline.fireChannelRead(byteBuf);
                } else {
                    byteBuf.release();
                }
            }
            allocHandle.readComplete();
            pipeline.fireChannelReadComplete();
            // 异常处理事件
            pipeline.fireExceptionCaught(cause);
            if (close || cause instanceof IOException) {
                closeOnRead(pipeline);
            }
        }

        /**
         * todo 重要 重要
         *  worker group的read操作
         * 此操作才是真正的读取操作
         */
        @Override
        public final void read() {
            final ChannelConfig config = config();
            if (shouldBreakReadReady(config)) {
                clearReadPending();
                return;
            }
            // 获取 pipeline
            final ChannelPipeline pipeline = pipeline();
            // 获取内存分配器
            final ByteBufAllocator allocator = config.getAllocator();
            // 接收内存分配器
            final RecvByteBufAllocator.Handle allocHandle = recvBufAllocHandle();
            // 把 一些 config中的 数据记录: 获取每次读取最大的字节数,读取的消息清零
            allocHandle.reset(config);
            ByteBuf byteBuf = null;
            boolean close = false;
            try {
                // while 循环, 持续从channel中读取数据
                do {
                    // 使用内存分配器 分配一块内存
                    byteBuf = allocHandle.allocate(allocator);
                    // doReadBytes 真实读取数据
                    // allocHandle.lastBytesRead 记录上次读取的字节数
                    allocHandle.lastBytesRead(doReadBytes(byteBuf));
                    // 如果没有读取到数据
                    if (allocHandle.lastBytesRead() <= 0) {
                        // nothing was read. release the buffer.
                        // 如果没有读取到数据呢  就释放buffer  并推出循环
                        byteBuf.release();
                        byteBuf = null;
                        close = allocHandle.lastBytesRead() < 0;
                        if (close) {
                            // There is nothing left to read as we received an EOF.
                            readPending = false;
                        }
                        break;
                    }
                    // 增加信息读取的次数
                    allocHandle.incMessagesRead(1);
                    readPending = false;
                    // 执行pipeline中 channelRead事件, 来对buf中的数据进行处理
                    // 也就是处理读取的数据
                    // 此处就是用户设置的 业务代码处理了
                    // bytebuf中就是读取到的数据
                    pipeline.fireChannelRead(byteBuf);
                    byteBuf = null;
                    /**
                     * 停止读取的条件:
                     * bytesToRead > 0 && maybeMoreDataSupplier.get() -- 此是下面的函数, 判断 attemptBytesRead 和 lastBytesRead是否相等
                     * private final UncheckedBooleanSupplier defaultMaybeMoreSupplier = new UncheckedBooleanSupplier() {
                     *             @Override
                     *             public boolean get() {
                     *                 return attemptBytesRead == lastBytesRead;
                     *             }
                     *         };
                     *
                     * 也就是 读取的字节数  等于 要读取的字节数
                     */
                } while (allocHandle.continueReading());
                // 读取完成  readComplete 事件
                allocHandle.readComplete();
                // 调用 readComplete事件
                // 用户自定义的  readComplete
                pipeline.fireChannelReadComplete();

                if (close) {
                    closeOnRead(pipeline);
                }
            } catch (Throwable t) {
                // 1.如果buf中仍然有数据,则对数据继续进行处理
                // 2.对异常进行处理
                handleReadException(pipeline, byteBuf, t, close, allocHandle);
            } finally {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254
                if (!readPending && !config.isAutoRead()) {
                    removeReadOp();
                }
            }
        }
    }

    /**
     * Write objects to the OS.
     * @param in the collection which contains objects to write.
     * @return The value that should be decremented from the write quantum which starts at
     * {@link ChannelConfig#getWriteSpinCount()}. The typical use cases are as follows:
     * <ul>
     *     <li>0 - if no write was attempted. This is appropriate if an empty {@link ByteBuf} (or other empty content)
     *     is encountered</li>
     *     <li>1 - if a single call to write data was made to the OS</li>
     *     <li>{@link ChannelUtils#WRITE_STATUS_SNDBUF_FULL} - if an attempt to write data was made to the OS, but no
     *     data was accepted</li>
     * </ul>
     * @throws Exception if an I/O exception occurs during write.
     */
    protected final int doWrite0(ChannelOutboundBuffer in) throws Exception {
        Object msg = in.current();
        if (msg == null) {
            // Directly return here so incompleteWrite(...) is not called.
            return 0;
        }
        // 写操作
        return doWriteInternal(in, in.current());
    }
    // 内部写入操作
    private int doWriteInternal(ChannelOutboundBuffer in, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            // 如果此byteBuf消息不可读,则删除
            if (!buf.isReadable()) {
                in.remove();
                return 0;
            }
            // 写入 并返回写入的字节数
            final int localFlushedAmount = doWriteBytes(buf);
            if (localFlushedAmount > 0) {
                // 更新一下处理进度
                in.progress(localFlushedAmount);
                if (!buf.isReadable()) {
                    // 1. 判断msg是否write完,完了则把flushedEntry unflushEntry tailEntry置为null
                    // 2. 没有写完,则flushEntry指向下一个要write的msg, 来进行write
                    in.remove();
                }
                return 1;
            }
        } else if (msg instanceof FileRegion) {
            FileRegion region = (FileRegion) msg;
            if (region.transferred() >= region.count()) {
                in.remove();
                return 0;
            }
            // 调用jdk来进行文件的写入
            // 使用 file.transferTo zero-copy 进行文件的写出
            long localFlushedAmount = doWriteFileRegion(region);
            if (localFlushedAmount > 0) {
                // 更新一下处理进度
                in.progress(localFlushedAmount);
                if (region.transferred() >= region.count()) {
                    in.remove();
                }
                return 1;
            }
        } else {
            // Should not reach here.
            throw new Error();
        }
        return WRITE_STATUS_SNDBUF_FULL;
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        // 获取自旋的次数;
        int writeSpinCount = config().getWriteSpinCount();
        do {
            // 获取第一个要write的msg
            Object msg = in.current();
            if (msg == null) {
                // Wrote all messages.
                // 没有消息可以了,
                clearOpWrite();
                // Directly return here so incompleteWrite(...) is not called.
                return;
            }
            writeSpinCount -= doWriteInternal(in, msg);
            // 自旋多次 来进行写入操作
        } while (writeSpinCount > 0);
        // 来根据自旋的剩余进一步处理
        // 1. 自旋大于0, 基本上可以判断msg写完了, 则标记channel的write事件
        // 2. 自旋小于0, 很有可能msg没有写完, 则清除当前的channel的write事件, 并提交一个任务,后续继续提交
        incompleteWrite(writeSpinCount < 0);
    }
    /*
         对写出的 msg类型 进行了一些过滤
     */
    @Override
    protected final Object filterOutboundMessage(Object msg) {
        // 1. 把byteBuf 封装为 directBuf
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            if (buf.isDirect()) {
                return msg;
            }

            return newDirectBuffer(buf);
        }
        // 2. 判断是否是 FileRegion
        if (msg instanceof FileRegion) {
            return msg;
        }
        // 3. 其他类型的则报错
        throw new UnsupportedOperationException(
                "unsupported message type: " + StringUtil.simpleClassName(msg) + EXPECTED_TYPES);
    }

    protected final void incompleteWrite(boolean setOpWrite) {
        // Did not write completely.
        // 没有写出完成
        // 半包写出
        if (setOpWrite) {
            // 设置channel的write事件
            setOpWrite();
        } else {
            // It is possible that we have set the write OP, woken up by NIO because the socket is writable, and then
            // use our write quantum. In this case we no longer want to set the write OP because the socket is still
            // writable (as far as we know). We will find out next time we attempt to write if the socket is writable
            // and set the write OP if necessary.
            // 清除此channel的selector的write事件
            // 如果 需要要设置 write 事件,则说明 完全写入了
            // 这里就清除 此channel对应的 OP_WRITE 事件
            clearOpWrite();

            // Schedule flush again later so other tasks can be picked up in the meantime
            // 提交一个任务, 在后面继续进行写入操作
            // 此 flushTask 会把刚写入到 outputBuffer中的数据 真实写入到 channel中,即真正的写出动作
            eventLoop().execute(flushTask);
        }
    }

    /**
     * Write a {@link FileRegion}
     *
     * @param region        the {@link FileRegion} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract long doWriteFileRegion(FileRegion region) throws Exception;

    /**
     * Read bytes into the given {@link ByteBuf} and return the amount.
     */
    protected abstract int doReadBytes(ByteBuf buf) throws Exception;

    /**
     * Write bytes form the given {@link ByteBuf} to the underlying {@link java.nio.channels.Channel}.
     * @param buf           the {@link ByteBuf} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract int doWriteBytes(ByteBuf buf) throws Exception;
    // 设置 nio的 OP_WRITE 事件
    protected final void setOpWrite() {
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        if (!key.isValid()) {
            return;
        }
        // 设置 OP_WRITE 事件
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) == 0) {
            key.interestOps(interestOps | SelectionKey.OP_WRITE);
        }
    }
    // 清除写标志
    protected final void clearOpWrite() {
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        // 如果有 写标志  那么就清除此标志位
        if ((interestOps & SelectionKey.OP_WRITE) != 0) {
            key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
        }
    }
}
