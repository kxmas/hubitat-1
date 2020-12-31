/**
   *
   *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
   *  in compliance with the License. You may obtain a copy of the License at:
   *
   *      http://www.apache.org/licenses/LICENSE-2.0
   *
   *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
   *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
   *  for the specific language governing permissions and limitations under the License.
   *
   * ------------------------------------------------------------------------------------------------------------------------------
   *
   * Purpose
   *    Monitors Octoprints MQTT topics for 3D printer status.
   *    Sets a switch for Started, Stopped (or Failed) for use in Rules. 
   *    shows % done print progress.
   *    Note: monitoring temperatures may send Hubitat too much, too fast.
   *      so I don't subscribe to that.
   *  
   */

import groovy.json.JsonSlurper 
import java.util.GregorianCalendar

metadata {
  definition (name: "Mqtt BT Monitor", namespace: "ccoupe", 
      author: "Cecil Coupe", 
      importURL: "https://raw.githubusercontent.com/ccoupe/hubitat/master/mqtt-bt-monitor.groovy"
    ) {
    capability "Initialize"
    capability "PresenceSensor"
   
 }

  preferences {
    input name: "MQTTBroker", type: "text", title: "MQTT Broker Address:", 
        required: true, displayDuringSetup: true
    input name: "username", type: "text", title: "MQTT Username:", 
        description: "(blank if none)", required: false, displayDuringSetup: true
    input name: "password", type: "password", title: "MQTT Password:",
        description: "(blank if none)", required: false, displayDuringSetup: true
    input name: "topicSub", type: "text", title: "Topic to Subscribe:", 
        description: "Example Topic (bt-monitor/lost_pi). Please don't use a #", 
        required: true, displayDuringSetup: true
    input name: "devName", type: "text", title: "Device Name",
        description: "Name or USE:MAC:IF:NO:NAME:GIVEN", required: true, 
        displayDuringSetup: true
    input name: "QOS", type: "text", title: "QOS Value:", required: false, 
        defaultValue: "1", displayDuringSetup: true
    input name: "retained", type: "bool", title: "Retain message:", required:false,
        defaultValue: false, displayDuringSetup: true
    input("logEnable", "bool", title: "Enable logging", required: true, defaultValue: true)
 }
}


def installed() {
    log.info "installed..."
}

// Parse incoming device messages to generate events
def parse(String description) {
  msg = interfaces.mqtt.parseMessage(description)
  topic = msg.get('topic')
  payload = msg.get('payload')
  def parser = new JsonSlurper()
  if (topic == "${settings?.topicSub}" && payload.startsWith('{')) {
    def pr_vals = parser.parseText(payload)
    if (pr_vals[settings.devName]) {
      def cf = pr_vals['confidence']
      if (cf == "100") {
        sendEvent(name: "presenceSensor", value: "present")
      } else if (cf == "0") {
        sendEvent(name: "presenceSensor", value: "not present")
      }
    }
  } 
}

def updated() {
  if (logEnable) log.info "Updated..."
  initialize()
}

def uninstalled() {
  if (logEnable) log.info "Disconnecting from mqtt"
  interfaces.mqtt.disconnect()
}

def initialize() {
	if (logEnable) runIn(900,debugOff) // clears debugging after 900 secs 
  if (logEnable) log.info "Initalize..."
	try {
    def mqttInt = interfaces.mqtt
    //open connection
    mqttbroker = "tcp://" + settings?.MQTTBroker + ":1883"
    mqttInt.connect(mqttbroker, "hubitat_${device}", settings?.username,settings?.password)
    //give it a chance to start
    pauseExecution(1000)
    log.info "Connection established"
    def topic = "${settings?.topicSub}/event/#"
    mqttInt.subscribe(topic)
		if (logEnable) log.debug "Subscribed to: ${topic}"
    topic = "${settings?.topicSub}/progress/printing"
    mqttInt.subscribe(topic)
		if (logEnable) log.debug "Subscribed to: ${topic}"
  } catch(e) {
    if (logEnable) log.debug "Initialize error: ${e.message}"
  }
}


def mqttClientStatus(String status) {
  if (status.startsWith("Error")) {
    def restart = false
    if (! interfaces.mqtt.isConnected()) {
      log.warn "mqtt isConnected false"
      restart = true
    }  else if (status.contains("lost")) {
      log.warn "mqtt Connection lost detected"
      restart = true
    } else {
      log.warn "mqtt error: ${status}"
    }
    if (restart) {
      def i = 0
      while (i < 60) {
        // wait for a minute for things to settle out, server to restart, etc...
        pauseExecution(1000*60)
        initialize()
        if (interfaces.mqtt.isConnected()) {
          log.warn "mqtt reconnect success!"
          break
        }
        i = i + 1
      }
    }
  } else {
    if (logEnable) log.warn "mqtt OK: ${status}"
  }
}

def debugOff(){
  log.warn "Debug logging disabled."
  device.updateSetting("logEnable",[value:"false",type:"bool"])
}
