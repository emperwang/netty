package com.wk.netty.codec.custom;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.nio.charset.Charset;

public class CustomClientHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        System.out.println("client write msg.");
        String sends = "hello Server.";
        SocketPackage socketPackage = new SocketPackage();
        socketPackage.setBytes(sends.getBytes(Charset.forName("utf-8")));
        socketPackage.setLength(sends.getBytes(Charset.forName("utf-8")).length);
        ctx.writeAndFlush(socketPackage);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        SocketPackage socketPackage = (SocketPackage) msg;
        String rec = new String(socketPackage.getBytes(), Charset.forName("utf-8"));
        System.out.println("client receive length: " + socketPackage.getLength()+", msg : "+
                rec);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }
}
