package x.mvmn.util.rpi.gsmutil;

import java.util.concurrent.ConcurrentLinkedQueue;

import jssc.SerialPort;
import jssc.SerialPortException;

import com.pi4j.io.gpio.GpioController;
import com.pi4j.io.gpio.GpioFactory;
import com.pi4j.io.gpio.GpioPinDigitalOutput;
import com.pi4j.io.gpio.PinState;
import com.pi4j.io.gpio.RaspiPin;

public class Sim900Helper {
	public static final char CTRL_Z = 0x1a;
	public static final String DEFAULT_PORT_NAME = "/dev/ttyAMA0";

	protected final SerialPort port;
	protected ConcurrentLinkedQueue<String> responses = new ConcurrentLinkedQueue<String>();

	public Sim900Helper() {
		this(DEFAULT_PORT_NAME);
	}

	public Sim900Helper(final String portName) {
		try {
			port = new SerialPort(portName);
			port.openPort();
			port.setParams(9600, 8, 1, 0);

			Thread readThread = new Thread() {
				public void run() {
					String line = null;
					while (true) {
						try {
							line = portRead();
						} catch (Exception e) {
						}
						if (line != null && !line.trim().isEmpty()) {
							responses.add(line);
						}
						ThreadHelper.ensuredSleep(1000);
					}
				}
			};
			readThread.setDaemon(true);
			readThread.start();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public String pollResponses() {
		return responses.poll();
	}

	public void clearResponses() {
		responses.clear();
	}

	public void portWrite(final String string) {
		try {
			port.writeString(string);
		} catch (SerialPortException e) {
			throw new RuntimeException(e);
		}
	}

	public String portRead() {
		try {
			return port.readString();
		} catch (SerialPortException e) {
			throw new RuntimeException(e);
		}
	}

	public void cmdAT() {
		portWrite("AT\r");
	}

	public void cmdSetSMSCenterNumber(final String smsCenterNumber) {
		portWrite("AT+CSCA=\"" + smsCenterNumber + "\"\r");
	}

	public void cmdSetSMSModeText() {
		portWrite("AT+CMGF=1\r");
	}

	public void cmdSendSMSMessage(final String number, final String text) {
		portWrite("AT+CMGS=\"" + number + "\"\r");
		portWrite(text + CTRL_Z);
	}

	public void cmdDial(final String number) {
		portWrite("ATD" + number + "\r");
	}

	public boolean ensureOn() {
		boolean result = false;
		cmdAT();
		ThreadHelper.ensuredSleep(3000);
		String line = pollResponses();
		if (line == null) {
			toggleOnOff();
			cmdAT();
			ThreadHelper.ensuredSleep(3000);
			line = pollResponses();
		}
		if (line.replaceAll("[\n\r]", "").trim().equals("AT OK")) {
			result = true;
		}
		return result;
	}

	public static void toggleOnOff() {
		GpioController gpio = GpioFactory.getInstance();
		GpioPinDigitalOutput pin = gpio.provisionDigitalOutputPin(RaspiPin.GPIO_00);
		pin.setState(PinState.LOW);
		ThreadHelper.ensuredSleep(3000);
		pin.setState(PinState.HIGH);
		ThreadHelper.ensuredSleep(3000);
		pin.setState(PinState.LOW);
		gpio.unprovisionPin(pin);
		gpio.shutdown();
	}
}
