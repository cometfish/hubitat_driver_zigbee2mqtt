/*
 * Zigbee2MQTT Gateway presence
 *
 * Monitors the status of the zigbee2mqtt gateway (https://github.com/koenkk/zigbee2mqtt) - requires an MQTT broker
 * Presence will update to 'not present' if the broker reports that the gateway has gone offline.
 * 
 */
metadata {
    definition (name: "Zigbee2MQTT Gateway", namespace: "community", author: "cometfish", importUrl: "https://raw.githubusercontent.com/cometfish/hubitat_driver_zigbee2mqtt/master/gatewaydriver.groovy") {
        capability "Initialize"
        capability "PresenceSensor"
        
        command "disconnect"
		
		attribute "presence", "enum", ["present", "not present"]
        attribute "lastJoinedDevice_FriendlyName", "string"
        attribute "lastJoinedDevice_Description", "string"
    }
}

preferences {
    section("URIs") {
        input "mqttBroker", "text", title: "MQTT Broker Address", required: true
		input "mqttTopic", "text", title: "Zigbee2MQTT Base Topic", defaultValue: 'zigbee2mqtt/', required: true
        input name: "mqttClientID", type: "text", title: "MQTT Client ID", required: true, defaultValue: "hubitat_zigbee2mqtt" 
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

def installed() {
    log.warn "installed..."
}

def parse(String description) {
    if (logEnable) log.debug description
	
    mqtt = interfaces.mqtt.parseMessage(description)
	if (logEnable) log.debug mqtt
	if (logEnable) log.debug mqtt.topic
	
    if (mqtt.topic == settings.mqttTopic + 'bridge/state') {
        if (mqtt.payload == 'online' && device.currentState("presence").value!="present") 
            sendEvent(name: "presence", value: "present", isStateChange: true)
        else if (mqtt.payload == 'offline' && device.currentState("presence").value!="not present")
            sendEvent(name: "presence", value: "not present", isStateChange: true)
    } else if (mqtt.topic == settings.mqttTopic + 'bridge/logging') {
        log.info mqtt.payload
    } else if (mqtt.topic == settings.mqttTopic + 'bridge/event')  {
        json = new groovy.json.JsonSlurper().parseText(mqtt.payload)
        if (logEnable) log.debug json
        if (json.type!=null && json.type=='device_interview' && json.data!=null && jason.data.status!=null && json.data.status=='successful') {
            //new device has successfully paired
            sendEvent(name: "lastJoinedDevice_FriendlyName", value: json.data.friendly_name, isStateChange: true)
            sendEvent(name: "lastJoinedDevice_Description", value: json.data.definition.description, isStateChange: true)
        }
    }
}

def updated() {
    log.info "updated..."
    initialize()
}

def disconnect() {
	if (logEnable) log.info "disconnecting from mqtt"
    interfaces.mqtt.disconnect()
    state.connected = false
}

def uninstalled() {
    disconnect() 
}

def initialize() {
    disconnect()

    try {
        //open connection
        def mqttInt = interfaces.mqtt
        mqttInt.connect("tcp://" + settings.mqttBroker, settings.mqttClientID, null, null)
        //give it a chance to start
        pauseExecution(1000)
        
        //need to set connected true here, because the state change
        // in mqttClientStatus is overwritten by the one in disconnect()
        state.connected=true
        if (logEnable) log.info "connection established"
        mqttInt.subscribe(settings.mqttTopic + "bridge/state")
        if (logEnable) mqttInt.subscribe(settings.mqttTopic + "bridge/logging")
    } catch(e) {
        log.debug "initialize error: ${e.message}"
    }
}

def mqttClientStatus(String status){
    log.debug "mqttStatus- error: ${status}"
    switch (status) {
        case "Status: Connection succeeded":
            state.connected = true
            break
        case "disconnected":
        case "Error: Connection lost: Connection lost":
            //note: this is NOT called when we deliberately disconnect, only on unexpected disconnect
            state.connected = false
            //try to reconnect after a small wait (so the broker being down doesn't send us into an endless loop of trying to reconnect and lock up the hub)
            runIn(5, initialize)
            break
        default:
            log.info status
            break
    }
}