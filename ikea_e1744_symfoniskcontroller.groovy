metadata {
    definition (name: "IKEA Symfonisk Sound Controller E1744 (Zigbee2MQTT)", namespace: "community", author: "cometfish") {

        capability "Initialize"
		capability "Actuator"
        capability "PushableButton"
        capability "ReleasableButton"
        capability "Battery"

        attribute "numberOfButtons", "number"
        attribute "pushed", "number"
        
        attribute "linkquality", "number"
        attribute "update_status", "string"
        
		command "disconnect"
    }
}

preferences {
    section("URIs") {
        input "mqttBroker", "string", title: "MQTT Broker Address", required: true
        input "mqttTopic", "string", title: "Zigbee2MQTT Base Topic", defaultValue: 'zigbee2mqtt/', required: true
        input "z2mName","string",title:"Device Friendly Name", required: true
        input "mqttClientID", "string", title: "MQTT Client ID", defaultValue: 'hubitat_e1744', required: true

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

    if (json.action=="toggle") {
        sendEvent(name: "pushed", value: 1, isStateChange:true)
    }
    else if (json.action=="brightness_step_up") {
        sendEvent(name: "pushed", value: 2, isStateChange:true)
    }
    else if (json.action=="brightness_step_down") {
        sendEvent(name: "pushed", value: 3, isStateChange:true)
    }
    else if (json.action=="brightness_move_up") {
        //twist clockwise
       sendEvent(name: "pushed", value: 4, isStateChange: true)
        if (state.lastHeld==5)
            sendEvent(name: "released", value: 5, isStateChange: true)
        state.lastHeld = 4
    }
    else if (json.action=="brightness_move_down") {
        //twist anticlockwise
       sendEvent(name: "pushed", value: 5, isStateChange: true)
         if (state.lastHeld==4)
        sendEvent(name: "released", value: 4, isStateChange: true)
        state.lastHeld = 5
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
    
    if (json.update!=null && json.update.state!=null && device.currentState("update_status").value!=json.update.state)
        sendEvent(name: "update_status", value: json.update.state, isStateChange: true)
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
    sendEvent(name: "numberOfButtons", value: 5, isStateChange:true)
    
    try {
        //open connection
        def mqttInt = interfaces.mqtt
        mqttInt.connect("tcp://" + settings.mqttBroker, settings.mqttClientID, null, null)

        //give it a chance to start
        pauseExecution(1000)

        if (logEnable) log.info "connection established"
        if (logEnable) log.info "subscribing to: "+settings.mqttTopic + settings.z2mName

        mqttInt.subscribe(settings.mqttTopic + settings.z2mName)
    } catch(e) {
        log.error "initialize error: ${e.message}"
    }
}

def mqttClientStatus(String status){
    if (logEnable) log.debug "mqttStatus: ${status}"
    switch (status) {
        case "Status: Connection succeeded":
            state.connected = true
            break
        case "disconnected":
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