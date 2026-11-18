// minecraft-web by chiikabu (https://github.com/chiikabu)

package oshi.hardware;

public interface GraphicsCard {
	String getName();
	String getVendor();
	String getDeviceId();
	long getVRam();
	String getVersionInfo();
}
