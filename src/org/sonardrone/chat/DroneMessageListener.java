package org.sonardrone.chat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.jivesoftware.smack.Chat;
import org.jivesoftware.smack.MessageListener;
import org.jivesoftware.smack.packet.Message;

import android.util.Log;

class InvalidMsgException extends Exception {
	public InvalidMsgException(String msg){
		super(msg);
	}
}

public class DroneMessageListener implements  MessageListener{
	private static final Set<String> ARGCOMMANDS = new HashSet<String>(Arrays.asList(
		     new String[] {"setRudderAngle","addWaypoint","setActive","setMotorLoad","setRudderAngle","setAutopilot"}
		));
	private static final String LOG_TAG = "DroneMessageListener";
	public ChatService service = null;
	
	public DroneMessageListener(ChatService srvc) {
		this.service=srvc;
	}
	
	public void processMessage(Chat chat, Message message) {
		Log.v(LOG_TAG,
				"incoming chat: " + message.getBody());
		final String[] msg = message.getBody().split(":");
		if(msg.length!=2) {
			Log.e(LOG_TAG,String.format(
					"Invalid command: %s",message.getBody()));
			return;
		}

		String cmd = msg[0];

		if(ARGCOMMANDS.contains(cmd) && msg.length!=2){
			Log.e(LOG_TAG,String.format("Command %s needs argument",cmd));
			return;
		}			
							
		try {
			if(cmd=="addWaypoint") {
				String[] coords=msg[1].split(",");
				try {	
					double lon= Double.valueOf(coords[0]);
					double lat = Double.valueOf(coords[1]);
					this.service.addWaypoint(lon, lat);
				} catch (Exception e) {
					throw new InvalidMsgException(String.format("Could not add waypoint: %s",msg[1]));
				}
			} 
			else if(cmd=="setMotorLoad"){
				try {
					int load = Integer.valueOf(msg[1]);
					this.service.setMotorLoad(load);				
				}catch (Exception e){
					throw new InvalidMsgException(String.format("Could not set load to: %s",msg[1]));
				}
			}
			else if(cmd=="setActive") {
				if (msg[1]=="true")
					this.service.setActive(true);
				else if(msg[1]=="false")
					this.service.setActive(false);
				else
					throw new InvalidMsgException("Could not activate/deactivate navigator");									
			}
			else if(cmd=="setAutopilot") {
				if (msg[1]=="true")
					this.service.setAutopilot(true);
				else if(msg[1]=="false")
					this.service.setAutopilot(false);
				else
					throw new InvalidMsgException("Could not start/stop autopilot");									
			}
			else if(cmd=="setRudderAngle") {
				try {
					int rudderAngle = Integer.valueOf(msg[1]);
					this.service.setRudderAngle(rudderAngle);
				} catch (Exception e) {
					throw new InvalidMsgException("Could not set rudderAngle");									
				}
			}
			else if(cmd=="startMotor")
				this.service.startMotor();
			else if(cmd=="stopMotor")
				this.service.stopMotor();
			else if(cmd=="sendStatus")
				this.service.sendStatus();
			else if(cmd=="shutDown")
				this.service.shutDown();
			else
				throw new InvalidMsgException("Invalid command");
		}	
		catch (InvalidMsgException e) {
			Log.e(LOG_TAG,e.getMessage());
			return;
		}	
	}
}
