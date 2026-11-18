// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel.local;

import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Queue;
import org.teavm.classlib.impl.Trampoline;

public class LocalChannel extends AbstractChannel {
	private final Queue<Object> outboundBuffer = new ArrayDeque<>();
	private LocalChannel peer;
	private SocketAddress localAddress;
	private SocketAddress remoteAddress;

	static void connect(final LocalChannel client, final LocalChannel serverSide, final LocalAddress serverAddress) {
		client.peer = serverSide;
		serverSide.peer = client;
		client.remoteAddress = serverAddress;
		client.localAddress = new LocalAddress("local:client");
		serverSide.localAddress = serverAddress;
		serverSide.remoteAddress = client.localAddress;
	}

	@Override
	public SocketAddress localAddress() {
		return this.localAddress;
	}

	@Override
	public SocketAddress remoteAddress() {
		return this.remoteAddress;
	}

	@Override
	protected void doWrite(final Object msg, final ChannelPromise promise) {
		if (!this.isOpen()) {
			ReferenceCountUtil.release(msg);
			promise.tryFailure(new ClosedChannelException());
			return;
		}
		this.outboundBuffer.add(msg);
		promise.trySuccess();
	}

	@Override
	protected void doFlush() {
		while (!this.outboundBuffer.isEmpty()) {
			final Object msg = this.outboundBuffer.poll();
			final LocalChannel target = this.peer;
			if (target == null || !target.isOpen()) {
				ReferenceCountUtil.release(msg);
				continue;
			}
			Trampoline.execute(() -> {
				if (target.isOpen()) {
					target.pipeline().fireChannelRead(msg);
				} else {
					ReferenceCountUtil.release(msg);
				}
			});
		}
	}

	@Override
	protected void doClose() {
		LocalChannel target = this.peer;
		this.peer = null;
		while (!this.outboundBuffer.isEmpty()) {
			ReferenceCountUtil.release(this.outboundBuffer.poll());
		}
		if (target != null) {
			target.close();
		}
	}
}
