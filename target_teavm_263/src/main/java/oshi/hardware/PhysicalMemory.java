// minecraft-web by chiikabu (https://github.com/chiikabu)

package oshi.hardware;

public interface PhysicalMemory {
	long getCapacity();
	long getClockSpeed();
	String getMemoryType();
	String getManufacturer();
}
