package com.wk.netty.codec.custom;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

public class CustomDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        int length = in.readInt();
        if (in.readableBytes() >= length){
            byte[] bytes = new byte[length];
            in.readBytes(bytes);
            SocketPackage socketPackage = new SocketPackage();
            socketPackage.setLength(length);
            socketPackage.setBytes(bytes);
            out.add(socketPackage);
        }
    }
}
