// minecraft-web by chiikabu (https://github.com/chiikabu)

package oshi.hardware;

public interface CentralProcessor {
	ProcessorIdentifier getProcessorIdentifier();
	int getLogicalProcessorCount();
	int getPhysicalProcessorCount();
	int getPhysicalPackageCount();
	long[][] getProcessorCpuLoadTicks();
	double[] getProcessorCpuLoadBetweenTicks(long[][] oldTicks);

	interface ProcessorIdentifier {
		String getName();
		String getVendor();
		String getIdentifier();
		String getMicroarchitecture();
		long getVendorFreq();
	}
}
