// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel;

import io.netty.buffer.ByteBufAllocator;
import java.util.NoSuchElementException;

public class DefaultChannelPipeline implements ChannelPipeline {
	private final Channel channel;
	private final AbstractChannelHandlerContext head;
	private final AbstractChannelHandlerContext tail;
	private int nameCounter;

	public DefaultChannelPipeline(final Channel channel, final ChannelOutboundHandler headHandler) {
		this.channel = channel;
		this.head = new AbstractChannelHandlerContext(this, "head", headHandler);
		this.tail = new AbstractChannelHandlerContext(this, "tail", new TailHandler());
		this.head.next = this.tail;
		this.tail.prev = this.head;
	}

	@Override
	public Channel channel() {
		return this.channel;
	}

	private String generateName(final ChannelHandler handler) {
		return handler.getClass().getName() + "#" + this.nameCounter++;
	}

	private AbstractChannelHandlerContext find(final String name) {
		for (AbstractChannelHandlerContext c = this.head.next; c != this.tail; c = c.next) {
			if (c.name.equals(name)) {
				return c;
			}
		}
		return null;
	}

	private AbstractChannelHandlerContext findOrThrow(final String name) {
		AbstractChannelHandlerContext ctx = this.find(name);
		if (ctx == null) {
			throw new NoSuchElementException(name);
		}
		return ctx;
	}

	private ChannelPipeline insertBefore(final AbstractChannelHandlerContext before, final String name, final ChannelHandler handler) {
		AbstractChannelHandlerContext ctx =
			new AbstractChannelHandlerContext(this, name != null ? name : this.generateName(handler), handler);
		ctx.prev = before.prev;
		ctx.next = before;
		before.prev.next = ctx;
		before.prev = ctx;
		try {
			handler.handlerAdded(ctx);
		} catch (Throwable var6) {
			this.fireExceptionCaught(var6);
		}
		return this;
	}

	@Override
	public ChannelPipeline addFirst(final String name, final ChannelHandler handler) {
		return this.insertBefore(this.head.next, name, handler);
	}

	@Override
	public ChannelPipeline addLast(final String name, final ChannelHandler handler) {
		return this.insertBefore(this.tail, name, handler);
	}

	@Override
	public ChannelPipeline addLast(final ChannelHandler... handlers) {
		for (ChannelHandler handler : handlers) {
			this.insertBefore(this.tail, null, handler);
		}
		return this;
	}

	@Override
	public ChannelPipeline addBefore(final String baseName, final String name, final ChannelHandler handler) {
		return this.insertBefore(this.findOrThrow(baseName), name, handler);
	}

	@Override
	public ChannelPipeline addAfter(final String baseName, final String name, final ChannelHandler handler) {
		return this.insertBefore(this.findOrThrow(baseName).next, name, handler);
	}

	@Override
	public ChannelHandler get(final String name) {
		AbstractChannelHandlerContext ctx = this.find(name);
		return ctx == null ? null : ctx.handler;
	}

	@Override
	public <T extends ChannelHandler> T get(final Class<T> handlerType) {
		java.util.Objects.requireNonNull(handlerType);
		for (AbstractChannelHandlerContext c = this.head.next; c != this.tail; c = c.next) {
			if (handlerType.isInstance(c.handler)) {
				return handlerType.cast(c.handler);
			}
		}
		return null;
	}

	@Override
	public ChannelHandlerContext context(final String name) {
		return this.find(name);
	}

	@Override
	public ChannelHandlerContext firstContext() {
		return this.head.next == this.tail ? null : this.head.next;
	}

	private void unlink(final AbstractChannelHandlerContext ctx) {
		ctx.prev.next = ctx.next;
		ctx.next.prev = ctx.prev;
		ctx.removed = true;
		try {
			ctx.handler.handlerRemoved(ctx);
		} catch (Throwable var3) {
			this.fireExceptionCaught(var3);
		}
	}

	@Override
	public ChannelHandler remove(final String name) {
		AbstractChannelHandlerContext ctx = this.findOrThrow(name);
		this.unlink(ctx);
		return ctx.handler;
	}

	@Override
	public ChannelPipeline remove(final ChannelHandler handler) {
		for (AbstractChannelHandlerContext c = this.head.next; c != this.tail; c = c.next) {
			if (c.handler == handler) {
				this.unlink(c);
				return this;
			}
		}
		throw new NoSuchElementException(handler.getClass().getName());
	}

	@Override
	public ChannelHandler replace(final String oldName, final String newName, final ChannelHandler newHandler) {
		AbstractChannelHandlerContext old = this.findOrThrow(oldName);
		this.insertBefore(old, newName, newHandler);
		this.unlink(old);
		return old.handler;
	}

	@Override
	public ChannelPipeline fireChannelActive() {
		AbstractChannelHandlerContext.invokeChannelActive(this.head.nextInbound());
		return this;
	}

	@Override
	public ChannelPipeline fireChannelInactive() {
		AbstractChannelHandlerContext.invokeChannelInactive(this.head.nextInbound());
		return this;
	}

	@Override
	public ChannelPipeline fireChannelRead(final Object msg) {
		AbstractChannelHandlerContext.invokeChannelRead(this.head.nextInbound(), msg);
		return this;
	}

	@Override
	public ChannelPipeline fireExceptionCaught(final Throwable cause) {
		AbstractChannelHandlerContext.invokeExceptionCaught(this.head.nextInbound(), cause);
		return this;
	}

	@Override
	public ChannelPipeline read() {
		AbstractChannelHandlerContext.invokeRead(this.tail.prevOutbound());
		return this;
	}

	@Override
	public ChannelPipeline flush() {
		AbstractChannelHandlerContext.invokeFlush(this.tail.prevOutbound());
		return this;
	}

	@Override
	public ChannelFuture write(final Object msg, final ChannelPromise promise) {
		AbstractChannelHandlerContext.invokeWrite(this.tail.prevOutbound(), msg, promise);
		return promise;
	}

	@Override
	public ChannelFuture writeAndFlush(final Object msg, final ChannelPromise promise) {
		this.write(msg, promise);
		this.flush();
		return promise;
	}

	private static final class TailHandler implements ChannelInboundHandler {
		@Override
		public void channelActive(final ChannelHandlerContext ctx) {
		}

		@Override
		public void channelInactive(final ChannelHandlerContext ctx) {
		}

		@Override
		public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
		}

		@Override
		public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
			cause.printStackTrace();
		}
	}

	static final class AbstractChannelHandlerContext implements ChannelHandlerContext {
		private final DefaultChannelPipeline pipeline;
		final String name;
		final ChannelHandler handler;
		AbstractChannelHandlerContext prev;
		AbstractChannelHandlerContext next;
		boolean removed;

		AbstractChannelHandlerContext(final DefaultChannelPipeline pipeline, final String name, final ChannelHandler handler) {
			this.pipeline = pipeline;
			this.name = name;
			this.handler = handler;
		}

		AbstractChannelHandlerContext nextInbound() {
			AbstractChannelHandlerContext c = this.next;
			while (!(c.handler instanceof ChannelInboundHandler)) {
				c = c.next;
			}
			return c;
		}

		AbstractChannelHandlerContext prevOutbound() {
			AbstractChannelHandlerContext c = this.prev;
			while (!(c.handler instanceof ChannelOutboundHandler)) {
				c = c.prev;
			}
			return c;
		}

		static void invokeChannelActive(final AbstractChannelHandlerContext ctx) {
			try {
				((ChannelInboundHandler)ctx.handler).channelActive(ctx);
			} catch (Throwable var2) {
				invokeExceptionCaught(ctx, var2);
			}
		}

		static void invokeChannelInactive(final AbstractChannelHandlerContext ctx) {
			try {
				((ChannelInboundHandler)ctx.handler).channelInactive(ctx);
			} catch (Throwable var2) {
				invokeExceptionCaught(ctx, var2);
			}
		}

		static void invokeChannelRead(final AbstractChannelHandlerContext ctx, final Object msg) {
			try {
				((ChannelInboundHandler)ctx.handler).channelRead(ctx, msg);
			} catch (Throwable var3) {
				invokeExceptionCaught(ctx, var3);
			}
		}

		static void invokeExceptionCaught(final AbstractChannelHandlerContext ctx, final Throwable cause) {
			try {
				((ChannelInboundHandler)ctx.handler).exceptionCaught(ctx, cause);
			} catch (Throwable var3) {
				var3.printStackTrace();
			}
		}

		static void invokeWrite(final AbstractChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) {
			try {
				((ChannelOutboundHandler)ctx.handler).write(ctx, msg, promise);
			} catch (Throwable var4) {
				promise.tryFailure(var4);
			}
		}

		static void invokeFlush(final AbstractChannelHandlerContext ctx) {
			try {
				((ChannelOutboundHandler)ctx.handler).flush(ctx);
			} catch (Throwable var2) {
				ctx.pipeline.fireExceptionCaught(var2);
			}
		}

		static void invokeRead(final AbstractChannelHandlerContext ctx) {
			try {
				((ChannelOutboundHandler)ctx.handler).read(ctx);
			} catch (Throwable var2) {
				ctx.pipeline.fireExceptionCaught(var2);
			}
		}

		@Override
		public Channel channel() {
			return this.pipeline.channel;
		}

		@Override
		public ChannelPipeline pipeline() {
			return this.pipeline;
		}

		@Override
		public String name() {
			return this.name;
		}

		@Override
		public ByteBufAllocator alloc() {
			return this.pipeline.channel.alloc();
		}

		@Override
		public ChannelHandlerContext fireChannelRead(final Object msg) {
			invokeChannelRead(this.nextInbound(), msg);
			return this;
		}

		@Override
		public ChannelHandlerContext fireChannelActive() {
			invokeChannelActive(this.nextInbound());
			return this;
		}

		@Override
		public ChannelHandlerContext fireChannelInactive() {
			invokeChannelInactive(this.nextInbound());
			return this;
		}

		@Override
		public ChannelHandlerContext fireExceptionCaught(final Throwable cause) {
			invokeExceptionCaught(this.nextInbound(), cause);
			return this;
		}

		@Override
		public ChannelHandlerContext read() {
			invokeRead(this.prevOutbound());
			return this;
		}

		@Override
		public ChannelHandlerContext flush() {
			invokeFlush(this.prevOutbound());
			return this;
		}

		@Override
		public ChannelPromise newPromise() {
			return new DefaultChannelPromise(this.pipeline.channel);
		}

		@Override
		public ChannelPromise voidPromise() {
			return this.pipeline.channel.voidPromise();
		}

		@Override
		public ChannelFuture write(final Object msg) {
			return this.write(msg, this.newPromise());
		}

		@Override
		public ChannelFuture write(final Object msg, final ChannelPromise promise) {
			invokeWrite(this.prevOutbound(), msg, promise);
			return promise;
		}

		@Override
		public ChannelFuture writeAndFlush(final Object msg) {
			return this.writeAndFlush(msg, this.newPromise());
		}

		@Override
		public ChannelFuture writeAndFlush(final Object msg, final ChannelPromise promise) {
			this.write(msg, promise);
			this.flush();
			return promise;
		}

		@Override
		public ChannelFuture close() {
			return this.pipeline.channel.close();
		}
	}
}
