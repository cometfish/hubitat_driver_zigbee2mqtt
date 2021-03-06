metadata {
    definition (name: "IKEA Tradfri Switch E1743 (Zigbee2MQTT)", namespace: "community", author: "cometfish", importUrl: "https://raw.githubusercontent.com/cometfish/hubitat_driver_zigbee2mqtt/master/ikea_e1743_tradfriswitch.groovy") {

        capability "Initialize"
		capability "Actuator"
        capability "PushableButton"
        capability "ReleasableButton"
        capability "Battery"
        capability "Switch"

		attribute "numberOfButtons", "number"
		attribute "pushed", "number"
        attribute "switch", "enum", ["on", "off"]
        attribute "last_bound", "string"

        attribute "linkquality", "number"
        attribute "update_status", "string"

		command "disconnect"
        command "bind", [[name: "Bind to", type: "STRING", description: "Group name, or friendly name for device", constraints: []]]
        command "unbind", [[name: "Unbind from", type: "STRING", description: "Group name, or friendly name for device", constraints: []]]
        command "rename", [[name: "Friendly name", type: "STRING", description: "New friendly name for device", constraints: []]]
    }
}

preferences {
    section("URIs") {
        input "mqttBroker", "text", title: "MQTT Broker Address", required: true
        input "mqttTopic", "text", title: "Zigbee2MQTT Base Topic", defaultValue: "zigbee2mqtt/", required: true
        input "z2mName", "text",title:"Device Friendly Name", required: true
        input "mqttClientID", "text", title: "MQTT Client ID", defaultValue: "hubitat_e1743", required: true

        input "logEnable", "bool", title: "Enable debug logging", defaultValue: true
    }
}

def installed() {
    log.warn "installed..."
}

def parse(String description) {
    mqtt = interfaces.mqtt.parseMessage(description)

	if (logEnable) log.debug mqtt
	if (logEnable) log.debug mqtt.topic

	json = new groovy.json.JsonSlurper().parseText(mqtt.payload)

	if (logEnable) log.debug json

	if (json.action=="on") {
		sendEvent(name: "switch", value: "on", isStateChange: true)
        sendEvent(name: "pushed", value: 1, isStateChange:true)
    } else if (json.action=="off") {
        sendEvent(name: "switch", value: "off", isStateChange: true)
        sendEvent(name: "pushed", value: 2, isStateChange:true)
    } else if (json.action=="brightness_move_up") {
        //hold on
       sendEvent(name: "pushed", value: 3, isStateChange: true)
        if (state.lastHeld==4)
            sendEvent(name: "released", value: 4, isStateChange: true)
        state.lastHeld = 3
    }
    else if (json.action=="brightness_move_down") {
        //hold off
       sendEvent(name: "pushed", value: 4, isStateChange: true)
         if (state.lastHeld==3)
        sendEvent(name: "released", value: 3, isStateChange: true)
        state.lastHeld = 4
    }
    else if (json.action=="brightness_stop") {
        //stopped twisting
        sendEvent(name: "released", value: state.lastHeld, isStateChange: true)
        state.lastHeld = 0
    }

    if (json.battery!=null)
        sendEvent(name: "battery", value: json.battery, isStateChange: true)

    if (json.linkquality!=null)
        sendEvent(name: "linkquality", value: json.linkquality, isStateChange: true)

    if (json.update!=null && json.update.state!=null) {
        if (json.update.state=="available") {
            if (!device.currentState("update_status").value.contains("available"))
                sendEvent(name: "update_status", value: json.update.state + " (<a href=\"https://ww8.ikea.com/ikeahomesmart/releasenotes/releasenotes.html\" target=\"_blank\">release notes</a>) (<a href=\"https://www.zigbee2mqtt.io/information/ota_updates.html#using-the-ikea-tradfri-test-server\" target=\"_blank\">ikea warning</a>)", isStateChange: true)
        } else if (device.currentState("update_status")==null || device.currentState("update_status").value!=json.update.state) {
            sendEvent(name: "update_status", value: json.update.state, isStateChange: true)
        }
    }
}

def updated() {
    log.info "updated..."
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
    sendEvent(name: "numberOfButtons", value: 4, isStateChange:true)
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

def bind(bindTo) {
    interfaces.mqtt.publish(settings.mqttTopic + "bridge/request/device/bind","{\"from\": \"${settings.z2mName}\", \"to\": \"${bindTo}\"}")
    sendEvent(name: "last_bound", value: bindTo, isStateChange:true)
}

def unbind(unbindFrom) {
    interfaces.mqtt.publish(settings.mqttTopic + "bridge/request/device/unbind","{\"from\": \"${settings.z2mName}\", \"to\": \"${unbindFrom}\"}")
    sendEvent(name: "last_bound", value: "", isStateChange:true)
}