package x.mvmn.util.rpi.gsmutil;

public interface MqttMessageListener {

	public void messageArrived(final String topic, final byte[] body);

}
