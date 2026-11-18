// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.handler.flow;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import java.util.ArrayDeque;
import java.util.Queue;

public class FlowControlHandler extends ChannelDuplexHandler {
	private final Queue<Object> queue = new ArrayDeque<>();
	private boolean draining;

	public FlowControlHandler() {
	}

	public FlowControlHandler(final boolean releaseMessages) {
	}

	public boolean isQueueEmpty() {
		return this.queue.isEmpty();
	}

	@Override
	public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
		this.queue.add(msg);
		this.dequeue(ctx);
	}

	@Override
	public void read(final ChannelHandlerContext ctx) {
		this.dequeue(ctx);
		ctx.read();
	}

	private void dequeue(final ChannelHandlerContext ctx) {
		if (this.draining) {
			return;
		}
		this.draining = true;
		try {
			while (!this.queue.isEmpty() && ctx.channel().config().isAutoRead()) {
				ctx.fireChannelRead(this.queue.poll());
			}
		} finally {
			this.draining = false;
		}
	}
}
