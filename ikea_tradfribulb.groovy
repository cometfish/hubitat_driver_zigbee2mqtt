metadata {
    definition(name: "IKEA Tradfri Bulb - Level Only (Zigbee2MQTT)", namespace: "community", author: "cometfish", importUrl: "https://raw.githubusercontent.com/cometfish/hubitat_driver_zigbee2mqtt/master/ikea_tradfribulb.groovy") {
        capability "Initialize"
        capability "Actuator"
		capability "Bulb"
        capability "Switch"
		capability "SwitchLevel"
        capability "Light"

		attribute "level", "number"
        attribute "linkquality", "number"
        attribute "last_bound", "string"

        command "refresh"
        command "disconnect"
		command "addToGroup", [[name: "Group", type: "STRING", description: "Group friendly name", constraints: []]]
        command "rename", [[name: "Friendly name", type: "STRING", description: "New friendly name for device", constraints: []]]
    }
}

preferences {
    section("URIs") {
        input "mqttBroker", "text", title: "MQTT Broker Address", required: true
        input "mqttTopic", "text", title: "Zigbee2MQTT Base Topic", defaultValue: "zigbee2mqtt/", required: true
        input "z2mName", "text",title:"Device Friendly Name", required: true
        input "mqttClientID", "text", title: "MQTT Client ID", defaultValue: "hubitat_tradfribulb", required: true

        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

def updated() {
    log.info "updated..."
    log.warn "debug logging is: ${logEnable == true}"
    initialize()
}

def disconnect() {
	log.info "disconnecting from mqtt"
    interfaces.mqtt.disconnect()
    state.connected = false
}

def uninstalled() {
    disconnect()
}

def initialize() {
    if (state.last_bound==null)
        sendEvent(name: "last_bound", value: "default_bind_group", isStateChange:true)
    
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
        if (logEnable) log.info "subscribing to: "+settings.mqttTopic + settings.z2mName

        mqttInt.subscribe(settings.mqttTopic + settings.z2mName)
    } catch(e) {
        log.error "initialize error: ${e}"
    }
}

def mqttClientStatus(String status){
    if (logEnable) log.debug "mqttStatus: ${status}"
    switch (status) {
        case "Status: Connection succeeded":
            state.connected = true
            break
        case "disconnected":
        case "Error: Connection lost: Connection lost":
            log.warn "MQTT connection lost"
            //note: this is NOT called when we deliberately disconnect, only on unexpected disconnect
            state.connected = false
            //try to reconnect after a small wait (so the broker being down doesn't send us into an endless loop of trying to reconnect and lock up the hub)
            runIn(5, initialize)
            break
        default:
            log.info "unknown: "+status
            break
    }
}

def rename(name) {
    if (logEnable) log.debug "Changing name from: ${settings.z2mName} to: ${name}"

    interfaces.mqtt.publish(settings.mqttTopic + "bridge/request/device/rename","{\"from\": \"${settings.z2mName}\", \"to\": \"${name}\"}")
    device.updateSetting("z2mName", [value:name, type:"text"])

    disconnect()
    initialize()
}


def parse(String description) {
    mqtt = interfaces.mqtt.parseMessage(description)

	if (logEnable) log.debug mqtt
	if (logEnable) log.debug mqtt.topic

	json = new groovy.json.JsonSlurper().parseText(mqtt.payload)

	if (logEnable) log.debug json

    if (json.state!=null) {
        sendEvent(name: "switch", value: json.state.toLowerCase(), isStateChange: true)
    }
    if (json.brightness!=null) {
        sendEvent(name: "level", value: json.brightness/254*100, isStateChange: true)
    }

    if (json.linkquality!=null)
        sendEvent(name: "linkquality", value: json.linkquality, isStateChange: true)
}

def on() {
    if (logEnable) log.debug "Sending on request"

    interfaces.mqtt.publish(settings.mqttTopic +settings.z2mName+ "/set","{\"state\": \"ON\"}")
    sendEvent(name: "switch", value: "on", isStateChange: true)
}

def off() {
    if (logEnable) log.debug "Sending off request"

    interfaces.mqtt.publish(settings.mqttTopic +settings.z2mName+ "/set","{\"state\": \"OFF\"}")
    sendEvent(name: "switch", value: "off", isStateChange: true)
}

def refresh() {
    if (logEnable) log.debug "Sending refresh request"

    interfaces.mqtt.publish(settings.mqttTopic +settings.z2mName+ "/get","{\"state\": \"\",\"brightness\":\"\"}")
}
def setLevel(level) {
    setLevel(level, null)
    }
def setLevel(level, duration) {
    if (logEnable) log.debug "Sending level request"
    
    b = level/100*254
    payload = "{\"brightness\":"+b
    if (duration!=null)
        payload += ", \"transition\":"+duration
    payload += "}"
    if (logEnable) log.debug payload
    interfaces.mqtt.publish(settings.mqttTopic +settings.z2mName+"/set", payload)
}

def addToGroup(group) {
    interfaces.mqtt.publish(settings.mqttTopic + "bridge/request/group/members/add","{\"device\": \"${settings.z2mName}\", \"group\": \"${group}\"}")
    sendEvent(name: "last_bound", value: group, isStateChange:true)
}