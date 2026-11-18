// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import java.util.HashMap;
import java.util.Map;

public abstract class AbstractChannel implements Channel {
	private final DefaultChannelPipeline pipeline;
	private final Map<AttributeKey<?>, Attribute<?>> attributes = new HashMap<>();
	private final DefaultChannelPromise closeFuture;
	private final ChannelPromise voidPromise;
	private final ChannelConfig config = new AbstractChannel.Config();
	private boolean open = true;
	private boolean active;
	private boolean autoRead = true;

	protected AbstractChannel() {
		this.pipeline = new DefaultChannelPipeline(this, new AbstractChannel.HeadHandler());
		this.closeFuture = new DefaultChannelPromise(this);
		this.voidPromise = new DefaultChannelPromise(this, true);
	}

	protected abstract void doWrite(Object msg, ChannelPromise promise);

	protected abstract void doFlush();

	protected abstract void doClose();

	protected void doRead() {
	}

	public void activate() {
		if (!this.active && this.open) {
			this.active = true;
			this.pipeline.fireChannelActive();
		}
	}

	@Override
	public ChannelPipeline pipeline() {
		return this.pipeline;
	}

	@Override
	public ChannelConfig config() {
		return this.config;
	}

	@Override
	public EventLoop eventLoop() {
		return InlineEventLoop.INSTANCE;
	}

	@Override
	public ByteBufAllocator alloc() {
		return ByteBufAllocator.DEFAULT;
	}

	@Override
	public boolean isOpen() {
		return this.open;
	}

	@Override
	public boolean isActive() {
		return this.active;
	}

	@Override
	public ChannelFuture close() {
		if (this.open) {
			this.open = false;
			boolean wasActive = this.active;
			this.active = false;
			this.doClose();
			if (wasActive) {
				this.pipeline.fireChannelInactive();
			}
			this.closeFuture.setSuccess();
		}
		return this.closeFuture;
	}

	@Override
	public ChannelFuture closeFuture() {
		return this.closeFuture;
	}

	@Override
	public Channel read() {
		this.pipeline.read();
		return this;
	}

	@Override
	public Channel flush() {
		this.pipeline.flush();
		return this;
	}

	@Override
	public ChannelPromise newPromise() {
		return new DefaultChannelPromise(this);
	}

	@Override
	public ChannelPromise voidPromise() {
		return this.voidPromise;
	}

	@Override
	public ChannelFuture write(final Object msg) {
		return this.write(msg, this.newPromise());
	}

	@Override
	public ChannelFuture write(final Object msg, final ChannelPromise promise) {
		return this.pipeline.write(msg, promise);
	}

	@Override
	public ChannelFuture writeAndFlush(final Object msg) {
		return this.writeAndFlush(msg, this.newPromise());
	}

	@Override
	public ChannelFuture writeAndFlush(final Object msg, final ChannelPromise promise) {
		return this.pipeline.writeAndFlush(msg, promise);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> Attribute<T> attr(final AttributeKey<T> key) {
		return (Attribute<T>)this.attributes.computeIfAbsent(key, AbstractChannel.DefaultAttribute::new);
	}

	@Override
	public <T> boolean hasAttr(final AttributeKey<T> key) {
		return this.attributes.containsKey(key);
	}

	private final class HeadHandler implements ChannelOutboundHandler {
		@Override
		public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) {
			AbstractChannel.this.doWrite(msg, promise);
		}

		@Override
		public void flush(final ChannelHandlerContext ctx) {
			AbstractChannel.this.doFlush();
		}

		@Override
		public void read(final ChannelHandlerContext ctx) {
			AbstractChannel.this.doRead();
		}
	}

	private final class Config implements ChannelConfig {
		@Override
		public boolean isAutoRead() {
			return AbstractChannel.this.autoRead;
		}

		@Override
		public ChannelConfig setAutoRead(final boolean value) {
			boolean wasOff = !AbstractChannel.this.autoRead;
			AbstractChannel.this.autoRead = value;
			if (value && wasOff) {
				AbstractChannel.this.pipeline.read();
			}
			return this;
		}

		@Override
		public boolean setOption(final ChannelOption<?> option, final Object value) {
			return true;
		}
	}

	private static final class DefaultAttribute<T> implements Attribute<T> {
		private final AttributeKey<T> key;
		private T value;

		@SuppressWarnings("unchecked")
		DefaultAttribute(final AttributeKey<?> key) {
			this.key = (AttributeKey<T>)key;
		}

		@Override
		public AttributeKey<T> key() {
			return this.key;
		}

		@Override
		public T get() {
			return this.value;
		}

		@Override
		public void set(final T value) {
			this.value = value;
		}
	}
}
