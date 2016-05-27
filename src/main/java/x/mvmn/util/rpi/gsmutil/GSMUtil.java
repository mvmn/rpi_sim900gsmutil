package x.mvmn.util.rpi.gsmutil;

import java.io.UnsupportedEncodingException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttSecurityException;

public class GSMUtil implements MqttMessageListener {

	private static final Logger LOGGER = Logger.getLogger(GSMUtil.class.getName());

	protected final Properties properties = new Properties();
	protected final MqttHandler mqttHandler;
	protected final Sim900Helper sim900helper = new Sim900Helper();

	public static void main(String args[]) throws Exception {

		final Properties overrides = new Properties();
		for (String arg : args) {
			if (arg.startsWith("-")) {
				final String[] splits = arg.split("==");
				final String key = splits[0].substring(1);
				final String val = splits.length > 1 ? splits[1] : "true";
				overrides.setProperty(key, val);
			}
		}
		new GSMUtil(overrides).startService();
	}

	public GSMUtil(final Properties overrides) {
		try {
			properties.load(this.getClass().getResourceAsStream("main.properties"));
			for (final Object key : overrides.keySet()) {
				properties.setProperty(key.toString(), overrides.getProperty(key.toString()));
			}
			String mqttProtocol = properties.getProperty("mqtt.protocol");
			String mqttHost = properties.getProperty("mqtt.host");
			int mqttPort = Integer.parseInt(properties.getProperty("mqtt.port"));
			String mqttUser = properties.getProperty("mqtt.user");
			String mqttPass = properties.getProperty("mqtt.pass");

			mqttHandler = new MqttHandler(mqttProtocol, mqttHost, mqttPort, mqttUser, mqttPass, this);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public void startService() throws MqttSecurityException, MqttException {
		mqttHandler.connect();
		mqttHandler.subscribe(properties.getProperty("mqtt.subscribe.topic"));
		int retries = 3;
		boolean done = false;
		do {
			done = sim900helper.ensureOn();
		} while (retries-- > 0 && !done);

		sim900helper.clearResponses();
		sim900helper.cmdAT();
		int waitTime = 5;
		while (sim900helper.responses.size() < 2 && waitTime-- > 0) {
			ThreadHelper.ensuredSleep(1000);
		}
		ThreadHelper.ensuredSleep(500);
		sim900helper.clearResponses();
		sim900helper.cmdSetSMSModeText();
		waitTime = 5;
		while (sim900helper.responses.size() < 2 && waitTime-- > 0) {
			ThreadHelper.ensuredSleep(1000);
		}
		ThreadHelper.ensuredSleep(500);
		sim900helper.clearResponses();
		sim900helper.cmdSetSMSEncodingGSM();
		waitTime = 5;
		while (sim900helper.responses.size() < 2 && waitTime-- > 0) {
			ThreadHelper.ensuredSleep(1000);
		}
		ThreadHelper.ensuredSleep(500);
	}

	public void messageArrived(String topic, byte[] body) {
		try {
			topic = topic.toLowerCase();
			final String bodyStr = new String(body, "UTF-8");

			if (topic.startsWith(properties.getProperty("mqtt.subscribe.topic").replaceAll("#", ""))) {
				final String command = topic.substring(properties.getProperty("mqtt.subscribe.topic").replaceAll("#", "").length());
				if (command.equals("power")) {
					if (bodyStr.equalsIgnoreCase("on")) {
						System.out.println("Ensure on: " + sim900helper.ensureOn());
					} else if (bodyStr.equalsIgnoreCase("off")) {
						System.out.println("Ensure on before toggle: " + sim900helper.ensureOn());
						Sim900Helper.toggleOnOff();
					} else if (bodyStr.equalsIgnoreCase("toggle")) {
						Sim900Helper.toggleOnOff();
					} else {
						LOGGER.log(Level.WARNING, "Unknown sim900 power command: " + bodyStr);
					}
				} else if (command.equals("sms/send")) {
					final int separatorIndex = bodyStr.indexOf(" ");
					final String number = bodyStr.substring(0, separatorIndex);
					final String text = bodyStr.substring(separatorIndex + 1);

					sim900helper.clearResponses();
					sim900helper.cmdSendSMSMessage(number, text);
					int waitTime = 5;
					while (sim900helper.responses.size() < 1 && waitTime-- > 0) {
						ThreadHelper.ensuredSleep(1000);
					}
					sim900helper.clearResponses();
					ThreadHelper.ensuredSleep(500);
				} else if (command.equals("getbalance")) {
					sim900helper.clearResponses();
					sim900helper.cmdDial("*111#");
					int waitTime = 5;
					while (sim900helper.responses.size() < 2 && waitTime-- > 0) {
						ThreadHelper.ensuredSleep(1000);
					}
					sim900helper.pollResponses();
					sim900helper.pollResponses();
					ThreadHelper.ensuredSleep(1000);
					waitTime = 5;
					while (sim900helper.responses.size() < 1 && waitTime-- > 0) {
						ThreadHelper.ensuredSleep(1000);
					}
					StringBuffer result = new StringBuffer();
					String line = null;
					do {
						line = sim900helper.responses.poll();
						if (line != null) {
							result.append(line).append(" ");
						}
					} while (line != null);
					String balanceResult = result.toString().replaceAll(".*Na rahunku\\s+([\\d\\\\.]+)\\s+grn.*", "$1");
					float balance = -1f;
					try {
						balance = Float.parseFloat(balanceResult);
					} catch (NumberFormatException nfe) {
						LOGGER.log(Level.WARNING, "Failed to parse balance response: " + balanceResult);
					}
					try {
						mqttHandler.send(properties.getProperty("mqtt.publish.topic") + "balance", "" + balance, null);
					} catch (Exception e) {
						LOGGER.log(Level.SEVERE, "Failed to send MQTT message", e);
					}
				} else if (command.equals("call")) {
					sim900helper.cmdDial(bodyStr.trim() + ";");
				}
			}
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
	}
}
