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
	public static final Character CTRL_Z = new Character((char) 0x1a);
	public static final String DEFAULT_PORT_NAME = "/dev/ttyAMA0";

	protected final String portName;
	protected final SerialPort port;
	protected ConcurrentLinkedQueue<String> responses = new ConcurrentLinkedQueue<String>();

	public Sim900Helper() {
		this(DEFAULT_PORT_NAME);
	}

	public Sim900Helper(final String portName) {
		this.portName = portName;
		try {
			port = new SerialPort(portName);
			port.openPort();
			port.setParams(9600, 8, 1, 0);

			Thread readThread = new Thread() {
				public void run() {
					String output = null;
					while (true) {
						try {
							output = portRead();
						} catch (Exception e) {
						}
						if (output != null && !output.trim().isEmpty()) {
							for (final String line : output.split("[\n\r]+")) {
								responses.add(line);
								if (responses.size() > 1000) {
									responses.poll();
								}
								System.out.println("<< " + portName + " << " + line.replace(CTRL_Z, '^'));
							}
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
			final String prefix;
			if (port.writeString(string)) {
				prefix = ">> ";
			} else {
				prefix = "xx ";
			}
			for (String line : string.split("[\n\r]+")) {
				System.out.println(prefix + portName + " >> " + line.replace(CTRL_Z, '^'));
			}
		} catch (SerialPortException e) {
			throw new RuntimeException(e);
		}
	}

	public void portWriteCtrlZ() {
		try {
			final String prefix;
			if (port.writeInt(0x1a)) {
				prefix = ">> ";
			} else {
				prefix = "xx ";
			}
			System.out.println(prefix + portName + " >> CTRL+Z");
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
		portWriteCtrlZ();
		portWrite("AT\r\n");
	}

	public void cmdSetSMSCenterNumber(final String smsCenterNumber) {
		portWriteCtrlZ();
		portWrite("AT+CSCA=\"" + smsCenterNumber + "\"\r\n");
	}

	public void cmdSetSMSModeText() {
		portWriteCtrlZ();
		portWrite("AT+CMGF=1\r\n");
	}

	public void cmdSetSMSEncodingGSM() {
		portWriteCtrlZ();
		portWrite("AT+CSCS=\"GSM\"\r\n");
	}

	public void cmdSendSMSMessage(final String number, final String text) {
		portWriteCtrlZ();
		portWrite("AT+CMGS=\"" + number + "\"\r\n");
		clearResponses();
		int waitTime = 30;
		while ((responses.size() < 1 || !pollResponses().equals(">")) && waitTime-- > 0) {
			ThreadHelper.ensuredSleep(100);
		}
		portWrite(text);
		portWriteCtrlZ();
		portWrite("\r\n");
	}

	public void cmdDial(final String number) {
		portWriteCtrlZ();
		portWrite("ATD" + number + "\r\n");
	}

	public boolean ensureOn() {
		boolean result = false;
		clearResponses();
		cmdAT();
		ThreadHelper.ensuredSleep(3000);
		String line = pollResponses();
		if (line == null) {
			toggleOnOff();
			cmdAT();
			ThreadHelper.ensuredSleep(3000);
			line = pollResponses();
		}
		if (line.equals("AT") && pollResponses().equals("OK")) {
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
