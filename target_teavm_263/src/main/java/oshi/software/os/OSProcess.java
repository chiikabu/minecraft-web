// minecraft-web by chiikabu (https://github.com/chiikabu)

package oshi.software.os;

public interface OSProcess {
	long getUserTime();
	long getKernelTime();
	long getUpTime();
	long getResidentSetSize();
	long getVirtualSize();
}
