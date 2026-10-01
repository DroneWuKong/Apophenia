using Toybox.Application.Storage;
using Toybox.Communications;
using Toybox.SensorHistory;
using Toybox.System;
using Toybox.Time;
using Toybox.WatchUi as Ui;

class ApopheniaTxListener extends Communications.ConnectionListener {
 function initialize(){ConnectionListener.initialize();}
 function onComplete(){Storage.deleteValue("pending_events");ApopheniaState.status="Sent to phone";}
 function onError(){ApopheniaState.status="Queued - send failed";}
}
class ApopheniaMenuDelegate extends Ui.Menu2InputDelegate {
 function initialize(){Menu2InputDelegate.initialize();}
 function onSelect(item){var id=item.getId();sendObservation(labelFor(id),kindFor(id));}
 private function labelFor(id){if(id.equals("weird")){return "That was weird";}if(id.equals("headache")){return "Headache";}if(id.equals("sinus")){return "Sinus / congestion";}if(id.equals("light")){return "Light changed";}if(id.equals("sound")){return "Sound / noise";}if(id.equals("body")){return "Body / sensation";}if(id.equals("coincidence")){return "Coincidence";}if(id.equals("hypothesis")){return "I think this happens when…";}return "Garmin observation";}
 private function kindFor(id){if(id.equals("weird")){return "WEIRD";}if(id.equals("coincidence")){return "COINCIDENCE";}if(id.equals("hypothesis")){return "HYPOTHESIS_NOTE";}return "OBSERVATION";}
 private function sendObservation(label,kind){
  var metrics={};addLatest(metrics,"garmin_heart_rate_bpm",:heartRate);addLatest(metrics,"garmin_stress",:stress);addLatest(metrics,"garmin_body_battery",:bodyBattery);addLatest(metrics,"garmin_spo2_pct",:oxygen);addLatest(metrics,"garmin_pressure_hpa",:pressure);addLatest(metrics,"garmin_temperature_c",:temperature);
  var settings=System.getDeviceSettings();metrics["garmin_phone_connected"]=settings.phoneConnected?1:0;
  var packet={"v"=>2,"type"=>"observation","kind"=>kind,"label"=>label,"ts_ms"=>Time.now().value()*1000,"metrics"=>metrics};
  var queue=Storage.getValue("pending_events");if(queue==null){queue=[];}if(queue.size()>=10){queue=queue.slice(1,null);}queue.add(packet);Storage.setValue("pending_events",queue);
  if(!settings.phoneConnected){ApopheniaState.status="Queued - phone offline";return;}
  ApopheniaState.status=queue.size()>1?"Sending queued events…":"Sending…";Communications.transmit(queue,{},new ApopheniaTxListener());
 }
 private function addLatest(metrics,key,kind){var value=latest(kind);if(value!=null){if(kind==:pressure){value=value/100.0;}metrics[key]=value;}}
 private function latest(kind){if(!(Toybox has :SensorHistory)){return null;}var iterator=null;if(kind==:heartRate&&(Toybox.SensorHistory has :getHeartRateHistory)){iterator=SensorHistory.getHeartRateHistory({:period=>1,:order=>SensorHistory.ORDER_NEWEST_FIRST});}else if(kind==:stress&&(Toybox.SensorHistory has :getStressHistory)){iterator=SensorHistory.getStressHistory({:period=>1,:order=>SensorHistory.ORDER_NEWEST_FIRST});}else if(kind==:bodyBattery&&(Toybox.SensorHistory has :getBodyBatteryHistory)){iterator=SensorHistory.getBodyBatteryHistory({:period=>1,:order=>SensorHistory.ORDER_NEWEST_FIRST});}else if(kind==:oxygen&&(Toybox.SensorHistory has :getOxygenSaturationHistory)){iterator=SensorHistory.getOxygenSaturationHistory({:period=>1,:order=>SensorHistory.ORDER_NEWEST_FIRST});}else if(kind==:pressure&&(Toybox.SensorHistory has :getPressureHistory)){iterator=SensorHistory.getPressureHistory({:period=>1,:order=>SensorHistory.ORDER_NEWEST_FIRST});}else if(kind==:temperature&&(Toybox.SensorHistory has :getTemperatureHistory)){iterator=SensorHistory.getTemperatureHistory({:period=>1,:order=>SensorHistory.ORDER_NEWEST_FIRST});}if(iterator==null){return null;}var sample=iterator.next();return sample==null?null:sample.data;}
}
