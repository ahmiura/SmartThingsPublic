/**
 *  Miura Xiaomi Mijia Smart Light Sensor
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
 *  Updates:
 *  -------
 *  05-20-2020 : Initial commit.
 */

import physicalgraph.zigbee.zcl.DataType

metadata {
    definition (name: "Miura Xiaomi Mijia Smart Light Sensor", namespace: "ahmiura", author: "ahmiura", ocfDeviceType: "oic.r.sensor.illuminance") {
        capability "Illuminance Measurement"
        capability "Configuration"
        capability "Refresh"
        capability "Battery"
        capability "Sensor"
        capability "Health Check"
    }

	preferences {
    	input "minReportSeconds", "number", title: "Min Report Time (0 to 3600 sec)", description: "Minimum seconds? (10 is default)", range: "0..3600", required: true, displayDuringSetup: true
        input "rawChange", "number", title: "Amount of change in raw data (1 to 1000)", description: "Amount of change? (21 is default)", range: "1..1000", required: true, displayDuringSetup: true
	}

	fingerprint profileId: "0104", inClusters: "0000,0400,0003,0001", outClusters: "0003", manufacturer: "LUMI", model: "lumi.sen_ill.mgl01", deviceJoinName: "Xiaomi Mijia Smart Home Light Sensor", ocfDeviceType: "oic.r.sensor.illuminance"    
    
    tiles(scale: 2) {
		multiAttributeTile(name:"illuminance", type: "generic", width: 6, height: 4){
			tileAttribute("device.illuminance", key: "PRIMARY_CONTROL") {
				attributeState("illuminance", label:'${currentValue} LUX', icon:"st.illuminance.illuminance.bright", backgroundColor:"#999999")
			}
		}
		standardTile("refresh", "device.illuminance", width: 2, height: 2, inactiveLabel: false, decoration: "flat") {
			state "default", label: 'Refresh', action:"refresh.refresh", icon:"st.secondary.refresh-icon"
		}
		standardTile("configure", "device.illuminance", width: 2, height: 2, inactiveLabel: false, decoration: "flat") {
			state "default", action:"configuration.configure", icon:"st.secondary.configure"
		}
		standardTile("battery", "device.battery", width: 2, height: 2, inactiveLabel: false, decoration: "flat") {
			state "default", label:'${currentValue}% battery', unit:"%"
		}
		main(["illuminance"])
		details(["illuminance", "battery", "refresh", "configure"])
	}
}

def parse(String description) {
	//log.debug "Incoming data from device : $description"
    log.debug "${device.displayName}: Parsing description: ${description}"
 
    // getEvent automatically retrieves temp and humidity in correct unit as integer
	Map map = zigbee.getEvent(description)
    
    if (description?.startsWith("catchall:")) {
		map = parseCatchAllMessage(description)
	}
    else if (description?.startsWith("read attr -")) {
		map = parseReadAttr(description)
	}
    else if (description?.startsWith("illuminance:")) {
        def raw = ((description - "illuminance: ").trim()) as int
        //def lux = zigbee.lux(raw as Integer).toString()
        log.debug "lux: ${raw}"
        sendEvent("name": "illuminance", "value": raw, "unit": "lux", "displayed": true, isStateChange: true)
	}
}


// Check catchall for battery voltage data to pass to getBatteryResult for conversion to percentage report
private Map parseCatchAllMessage(String description) {
	Map resultMap = [:]
	def catchall = zigbee.parse(description)
	log.debug "catchall: ${catchall}"

	if (catchall.clusterId == 0x0000) {
		def MsgLength = catchall.data.size()
		// Xiaomi CatchAll does not have identifiers, first UINT16 is Battery
		if ((catchall.data.get(0) == 0x01 || catchall.data.get(0) == 0x02) && (catchall.data.get(1) == 0xFF)) {
			for (int i = 4; i < (MsgLength-3); i++) {
				if (catchall.data.get(i) == 0x21) { // check the data ID and data type
					// next two bytes are the battery voltage
					resultMap = getBatteryResult((catchall.data.get(i+2)<<8) + catchall.data.get(i+1))
					break
				}
			}
		}
	}
	return resultMap
}

// Parse device name on short press of reset button
private Map parseReadAttr(String description) {
    Map resultMap = [:]

    def cluster = description.split(",").find {it.split(":")[0].trim() == "cluster"}?.split(":")[1].trim()
    def attrId = description.split(",").find {it.split(":")[0].trim() == "attrId"}?.split(":")[1].trim()
    def value = description.split(",").find {it.split(":")[0].trim() == "value"}?.split(":")[1].trim()
    
    log.debug "cluster: ${cluster}"
    log.debug "attrId: ${attrId}"
    log.debug "value: ${value}"

    if (cluster == "0001" && attrId == "0020") {
    	def vBatt = Integer.parseInt(value,16) / 10
        def pct = (vBatt - 2.1) / (3 - 2.1)
        def roundedPct = Math.round(pct * 100)
        if (roundedPct <= 0) roundedPct = 1
        	def batteryValue = Math.min(100, roundedPct)
            sendEvent("name": "battery", "value": batteryValue, "displayed": true, isStateChange: true)
	} else {
    	log.debug "UNKNOWN Cluster and Attribute : $descMap"
    }
}

// Convert raw 4 digit integer voltage value into percentage based on minVolts/maxVolts range
private Map getBatteryResult(rawValue) {
    // raw voltage is normally supplied as a 4 digit integer that needs to be divided by 1000
    // but in the case the final zero is dropped then divide by 100 to get actual voltage value
    def rawVolts = rawValue / 1000
    def minVolts
    def maxVolts

    if(voltsmin == null || voltsmin == "")
    	minVolts = 2.5
    else
   	minVolts = voltsmin

    if(voltsmax == null || voltsmax == "")
    	maxVolts = 3.0
    else
	maxVolts = voltsmax

    def pct = (rawVolts - minVolts) / (maxVolts - minVolts)
    def roundedPct = Math.min(100, Math.round(pct * 100))

    def result = [
        name: 'battery',
        value: roundedPct,
        unit: "%",
        isStateChange:true,
        descriptionText : "${device.displayName} Battery at ${roundedPct}% (${rawVolts} Volts)"
    ]

    return result
}

def installed() {
	configure()
}

def updated() {
	configure()
}

def refresh() {
	log.debug "Refreshing values..."
	[
        "st rattr 0x${device.deviceNetworkId} 1 0x001 0", "delay 200",
        "st rattr 0x${device.deviceNetworkId} 1 0x400 0", "delay 200"
	]
}

def configure() {
	log.debug "Configuration starting..."
   	def minSeconds = minReportSeconds.intValue()
   	def delta = rawChange.intValue()
    log.debug "Pref values : $minSeconds minimum seconds and $delta amount of change"
	sendEvent(name: "checkInterval", value: 2 * 60 * 60 + 2 * 60, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID, offlinePingable: "1"])
    log.debug "...bindings..."
	[
		"zdo bind 0x${device.deviceNetworkId} 1 1 0x000 {${device.zigbeeId}} {}", "delay 1000",	// basic cluster
        "zdo bind 0x${device.deviceNetworkId} 1 1 0x001 {${device.zigbeeId}} {}", "delay 1000",	// power cluster
		"zdo bind 0x${device.deviceNetworkId} 1 1 0x003 {${device.zigbeeId}} {}", "delay 1000",	// identify cluster
		"zdo bind 0x${device.deviceNetworkId} 1 1 0x400 {${device.zigbeeId}} {}", "delay 1000",	// illuminance cluster
		"send 0x${device.deviceNetworkId} 1 1"
	]
    log.debug "...reporting intervals..."
    [
        zigbee.configureReporting(0x0001, 0x0020, 0x20, 60, 3600, 0x01), "delay 1000",	// power cluster (get battery voltage every hour, or if it changes)
        zigbee.configureReporting(0x0400, 0x0000, 0x21, minSeconds, 3600, delta)		// illuminance cluster (min report time via preferences, max 3600 seconds (1 hour), raw amount of change via preferences)
	]
}