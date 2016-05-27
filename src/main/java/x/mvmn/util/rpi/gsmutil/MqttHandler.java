package x.mvmn.util.rpi.gsmutil;

import java.io.UnsupportedEncodingException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;

public class MqttHandler {

	private static final Logger LOGGER = Logger.getLogger(MqttHandler.class.getName());

	protected IMqttAsyncClient client;

	protected final String protocol;
	protected final String host;
	protected final int port;
	protected final String username;
	protected final byte[] password;
	protected int connectionRetryIntervalSeconds = 10;
	protected int connectionTimeoutSeconds = 30;
	protected final ConcurrentLinkedQueue<String> topicsToSubscribeTo = new ConcurrentLinkedQueue<String>();

	public MqttHandler(final String protocol, final String host, final int port, final String username, final String password,
			final MqttMessageListener listener) throws MqttException {
		this.protocol = protocol;
		this.host = host;
		this.port = port;
		this.username = username;
		try {
			this.password = password.getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e); // Will never happen
		}

		client = new MqttAsyncClient(protocol + "://" + host + ":" + port, "gsmutil" + MqttClient.generateClientId());

		client.setCallback(new MqttCallback() {
			public void connectionLost(Throwable cause) {
				onConnectionLost(cause);
			}

			public void messageArrived(String topic, MqttMessage message) throws Exception {
				try {
					listener.messageArrived(topic, message.getPayload());
				} catch (Throwable t) {
					LOGGER.log(Level.SEVERE, "Failure when processing incoming MQTT message.", t);
				}
			}

			public void deliveryComplete(IMqttDeliveryToken token) {
			}
		});
	}

	public void connect() {
		final MqttConnectOptions options = new MqttConnectOptions();
		options.setConnectionTimeout(connectionTimeoutSeconds);
		if (username != null && !username.isEmpty()) {
			options.setUserName(username);
		}
		if (password != null && password.length > 0) {
			try {
				options.setPassword(new String(password, "UTF-8").toCharArray());
			} catch (UnsupportedEncodingException e) {
				throw new RuntimeException(e); // Will never happen
			}
		}

		new ReconnectThread(client, connectionRetryIntervalSeconds, options).run();
	}

	public void send(final String topic, final String message, final IMqttActionListener callback) throws MqttPersistenceException, MqttException {
		try {
			client.publish(topic, message.getBytes("UTF-8"), 1, false, null, callback);
		} catch (UnsupportedEncodingException e) {
		}
	}

	protected static class ReconnectThread extends Thread implements IMqttActionListener {
		protected final IMqttAsyncClient client;
		protected final int connectionRetryIntervalSeconds;
		protected final MqttConnectOptions options;

		public ReconnectThread(final IMqttAsyncClient client, final int connectionRetryIntervalSeconds, final MqttConnectOptions options) {
			this.client = client;
			this.connectionRetryIntervalSeconds = connectionRetryIntervalSeconds;
			this.options = options;
		}

		public void run() {
			while (!client.isConnected()) {
				LOGGER.log(Level.WARNING, "Connecting to MQTT broker.");
				try {
					client.connect(options, null, this).waitForCompletion();
				} catch (Throwable t) {
					LOGGER.log(Level.WARNING, "Failed to connect to MQTT broker. Retrying in " + connectionRetryIntervalSeconds + " seconds.", t);
					try {
						Thread.sleep(connectionRetryIntervalSeconds * 1000);
					} catch (InterruptedException e) {
						// Ignore
					}
				}
			}
		}

		public void onSuccess(IMqttToken asyncActionToken) {
			LOGGER.log(Level.INFO, "Connected to MQTT broker.");
		}

		public void onFailure(IMqttToken asyncActionToken, Throwable exception) {

		}
	}

	protected void onConnectionLost(final Throwable cause) {
		LOGGER.log(Level.WARNING, "Connection to MQTT broker was lost. Retrying connection...", cause);
		connect();
		for (final String topic : topicsToSubscribeTo) {
			subscribe(topic);
		}
	}

	public void subscribe(final String topic) {
		topicsToSubscribeTo.add(topic);
		try {
			this.client.subscribe(topic, 1).waitForCompletion();
			LOGGER.log(Level.INFO, "Subscribed to MQTT topic " + topic);
		} catch (MqttException e) {
			throw new RuntimeException("MQTT client failed to subscribe to topic " + topic, e);
		}
	}

	public void unsubscribe(final String topic) {
		topicsToSubscribeTo.remove(topic);
		try {
			this.client.unsubscribe(topic).waitForCompletion();
			LOGGER.log(Level.INFO, "Unsubscribed from MQTT topic " + topic);
		} catch (MqttException e) {
			throw new RuntimeException("MQTT client failed to unsubscribe from topic " + topic, e);
		}
	}

	public int getConnectionRetryIntervalSeconds() {
		return connectionRetryIntervalSeconds;
	}

	public void setConnectionRetryIntervalSeconds(int connectionRetryIntervalSeconds) {
		this.connectionRetryIntervalSeconds = connectionRetryIntervalSeconds;
	}

	public int getConnectionTimeoutSeconds() {
		return connectionTimeoutSeconds;
	}

	public void setConnectionTimeoutSeconds(int connectionTimeoutSeconds) {
		this.connectionTimeoutSeconds = connectionTimeoutSeconds;
	}
}
