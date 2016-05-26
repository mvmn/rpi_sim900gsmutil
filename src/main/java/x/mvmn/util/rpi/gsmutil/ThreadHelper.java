package x.mvmn.util.rpi.gsmutil;

public class ThreadHelper {

	public static void ensuredSleep(long sleepTime) {
		long startTime = System.currentTimeMillis();
		do {
			try {
				Thread.sleep(sleepTime);
			} catch (InterruptedException e) {
				sleepTime -= System.currentTimeMillis() - startTime;
				if (sleepTime <= 0) {
					sleepTime = 1;
				}
			}
		} while (System.currentTimeMillis() - startTime < sleepTime);
	}
}
