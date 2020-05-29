package com.wk.netty.codec.custom;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.CharsetUtil;

import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class CustomServerHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        Channel channel = ctx.channel();
        System.out.println(channel.remoteAddress()+"  online..");
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        SocketPackage socketPackage = (SocketPackage) msg;
        String rec = new String(socketPackage.getBytes(), Charset.forName("utf-8"));
        System.out.println("server receive length: " + socketPackage.getLength()+", msg : "+
                rec);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        String response = "server receve {"+ rec + "} , at time: "+ LocalDateTime.now().format(formatter);
        byte[] bytes = response.getBytes(CharsetUtil.UTF_8);
        socketPackage.setLength(bytes.length);
        socketPackage.setBytes(bytes);
        ctx.writeAndFlush(socketPackage);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }
}
