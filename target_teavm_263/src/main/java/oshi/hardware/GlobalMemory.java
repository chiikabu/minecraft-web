// minecraft-web by chiikabu (https://github.com/chiikabu)

package oshi.hardware;

import java.util.List;

public interface GlobalMemory {
	long getTotal();
	long getAvailable();
	VirtualMemory getVirtualMemory();
	List<PhysicalMemory> getPhysicalMemory();
}
