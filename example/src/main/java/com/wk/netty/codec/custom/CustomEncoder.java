package com.wk.netty.codec.custom;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public class CustomEncoder extends MessageToByteEncoder<SocketPackage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, SocketPackage msg, ByteBuf out) throws Exception {
        if (msg != null){
            System.out.println("begin to encode.");
            out.writeInt(msg.getLength());
            out.writeBytes(msg.getBytes());
        }
    }
}
