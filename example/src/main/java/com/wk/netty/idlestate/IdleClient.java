package com.wk.netty.idlestate;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;

public class IdleClient {
    public static void main(String[] args) {
        NioEventLoopGroup child = new NioEventLoopGroup();
        try{
            Bootstrap handler = new Bootstrap()
                    .channel(NioSocketChannel.class)
                    .group(child)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(
                                    new IdleStateHandler(3,5,8));
                            pipeline.addLast(new ChildHandler());
                        }
                    });
            ChannelFuture channelFuture = handler.connect("127.0.0.1", 8080).sync();
            System.out.println("client ready ...");
            channelFuture.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            child.shutdownGracefully();
        }
    }
}
