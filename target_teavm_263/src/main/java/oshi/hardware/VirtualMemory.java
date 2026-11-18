// minecraft-web by chiikabu (https://github.com/chiikabu)

package oshi.hardware;

public interface VirtualMemory {
	long getVirtualMax();
	long getVirtualInUse();
	long getSwapTotal();
	long getSwapUsed();
}
