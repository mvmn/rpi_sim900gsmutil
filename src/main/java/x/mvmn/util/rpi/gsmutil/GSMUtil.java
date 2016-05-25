package x.mvmn.util.rpi.gsmutil;

import java.util.Properties;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttSecurityException;

public class GSMUtil implements MqttMessageListener {

	protected final Properties properties = new Properties();
	protected final MqttHandler mqttHandler;

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
	}

	public void messageArrived(String topic, byte[] body) {
		// TODO Auto-generated method stub

	}
}
