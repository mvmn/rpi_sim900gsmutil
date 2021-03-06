package x.mvmn.util.rpi.gsmutil;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttSecurityException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class GSMUtil implements MqttMessageListener {

	private static final Logger LOGGER = Logger.getLogger(GSMUtil.class.getName());
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

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
		System.out.println("Connection to MQTT broker");
		mqttHandler.connect();
		System.out.println("Subscribing to topics");
		mqttHandler.subscribe(properties.getProperty("mqtt.subscribe.topic"));
		System.out.println("Ensuring GSM module is on");
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
		System.out.println("Setting SMS mode to text");
		sim900helper.cmdSetSMSModeText();
		waitTime = 5;
		while (sim900helper.responses.size() < 2 && waitTime-- > 0) {
			ThreadHelper.ensuredSleep(1000);
		}
		ThreadHelper.ensuredSleep(500);
		sim900helper.clearResponses();

		System.out.println("Setting SMS encoding to GSM");
		sim900helper.cmdSetSMSEncodingGSM();
		waitTime = 5;
		while (sim900helper.responses.size() < 2 && waitTime-- > 0) {
			ThreadHelper.ensuredSleep(1000);
		}
		ThreadHelper.ensuredSleep(500);
		System.out.println("Finished initializing. GSM module indicated working: " + done);
	}

	public void messageArrived(String topic, byte[] body) {
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> payload = (Map<String, Object>) GSON.fromJson(new String(body, "UTF-8"), Map.class);

			// int idx = Integer.parseInt(payload.get("idx").toString());
			int nvalue = (int) Double.parseDouble(payload.get("nvalue").toString());
			String name = payload.get("name").toString();

			if (name.equals(properties.getProperty("sensor.sim900.name"))) {
				if (nvalue == 1) {
					System.out.println("Ensure on: " + sim900helper.ensureOn());
				} else if (nvalue == 0) {
					System.out.println("Ensure on before toggle: " + sim900helper.ensureOn());
					Sim900Helper.toggleOnOff();
				} else if (nvalue == 2) {
					Sim900Helper.toggleOnOff();
				} else {
					LOGGER.log(Level.WARNING, "Unknown sim900 power command: " + nvalue);
				}
			} else if (name.equals(properties.getProperty("sensor.door.name")) && nvalue == 0) {
				final String number = properties.getProperty("phonenum");
				final String text = properties.getProperty("sensor.door.msgtext", "Door was open!");

				sim900helper.clearResponses();
				sim900helper.cmdSendSMSMessage(number, text);
				int waitTime = 5;
				while (sim900helper.responses.size() < 1 && waitTime-- > 0) {
					ThreadHelper.ensuredSleep(1000);
				}
				sim900helper.clearResponses();
				ThreadHelper.ensuredSleep(500);
			} else if (name.equals(properties.getProperty("sensor.balance.name"))) {
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
				System.out.println("Parsing message: " + result);
				String balanceResult = result.toString().replaceAll("\n+", "").replaceAll(".*Na rahunku\\s+([\\d\\\\.]+)\\s+grn.*", "$1");
				float balance = -1f;
				try {
					balance = Float.parseFloat(balanceResult);
				} catch (NumberFormatException nfe) {
					LOGGER.log(Level.WARNING, "Failed to parse balance response: " + balanceResult);
				}
				try {
					Map<String, Object> balanceMessage = new HashMap<String, Object>();
					balanceMessage.put("svalue", String.valueOf(balance));
					balanceMessage.put("nvalue", 0);
					balanceMessage.put("idx", Integer.parseInt(properties.getProperty("mqtt.balance.idx", "1")));
					mqttHandler.send(properties.getProperty("mqtt.publish.topic"), GSON.toJson(balanceMessage), null);
				} catch (Exception e) {
					LOGGER.log(Level.SEVERE, "Failed to send MQTT message", e);
				}
			} else if (name.equals(properties.getProperty("sensor.bell.name")) && nvalue == 1) {
				final String number = properties.getProperty("phonenum");
				sim900helper.cmdDial(number + ";");
			}
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
	}
}
