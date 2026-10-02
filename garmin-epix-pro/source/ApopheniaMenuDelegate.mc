using Toybox.Application.Storage;
using Toybox.Communications;
using Toybox.SensorHistory;
using Toybox.System;
using Toybox.Time;
using Toybox.Lang;
using Toybox.WatchUi as Ui;

class ApopheniaTxListener extends Communications.ConnectionListener {
 function initialize(){ConnectionListener.initialize();}
 function onComplete(){ApopheniaDelivery.complete();}
 function onError(){ApopheniaDelivery.failed();}
}

module ApopheniaDelivery {
 var queue = null;
 function pending() {
  if(queue == null){queue = new PendingEvents(Storage.getValue("pending_events"));}
  return queue;
 }
 function enqueue(packet) {
  if(!pending().append(packet)){
   ApopheniaState.setStatus("Queue full - NOT recorded");
   return;
  }
  Storage.setValue("pending_events",pending().events);
  flush();
 }
 function flush() {
  if(pending().events.size()==0 || pending().sentCount!=0){return;}
  if(!System.getDeviceSettings().phoneConnected){
   ApopheniaState.setStatus("Queued - phone offline");return;
  }
  var batch=pending().begin();
  ApopheniaState.setStatus("Sending queued events…");
  try { Communications.transmit(batch,{},new ApopheniaTxListener()); }
  catch(error) { failed(); }
 }
 function complete() {
  pending().complete();
  Storage.setValue("pending_events",pending().events);
  ApopheniaState.setStatus("Sent to phone");
  flush();
 }
 function failed() {
  pending().failed();
  ApopheniaState.setStatus("Queued - send failed");
 }
}
class ApopheniaMenuDelegate extends Ui.Menu2InputDelegate {
 function initialize(){Menu2InputDelegate.initialize();}
 function onSelect(item){var id=item.getId();if(id.equals("retry")){ApopheniaDelivery.flush();return;}sendObservation(labelFor(id),kindFor(id));}
 private function labelFor(id){if(id.equals("weird")){return "That was weird";}if(id.equals("headache")){return "Headache";}if(id.equals("sinus")){return "Sinus / congestion";}if(id.equals("light")){return "Light changed";}if(id.equals("sound")){return "Sound / noise";}if(id.equals("body")){return "Body / sensation";}if(id.equals("coincidence")){return "Coincidence";}if(id.equals("hypothesis")){return "I think this happens when…";}return "Garmin observation";}
 private function kindFor(id){if(id.equals("weird")){return "WEIRD";}if(id.equals("coincidence")){return "COINCIDENCE";}if(id.equals("hypothesis")){return "HYPOTHESIS_NOTE";}return "OBSERVATION";}
 private function sendObservation(label,kind){
  var metrics={};addLatest(metrics,"garmin_heart_rate_bpm",:heartRate);addLatest(metrics,"garmin_stress",:stress);addLatest(metrics,"garmin_body_battery",:bodyBattery);addLatest(metrics,"garmin_spo2_pct",:oxygen);addLatest(metrics,"garmin_pressure_hpa",:pressure);addLatest(metrics,"garmin_temperature_c",:temperature);
  var settings=System.getDeviceSettings();metrics["garmin_phone_connected"]=settings.phoneConnected?1:0;
  var packet={"v"=>2,"type"=>"observation","kind"=>kind,"label"=>label,"ts_ms"=>Time.now().value().toLong()*1000l,"metrics"=>metrics};
  ApopheniaDelivery.enqueue(packet);
 }
 private function addLatest(metrics,key,kind){var value=latest(kind);if(value!=null){if(kind==:pressure){value=value/100.0;}metrics[key]=value;}}
 private function latest(kind){if(!(Toybox has :SensorHistory)){return null;}var iterator=null;if(kind==:heartRate&&(Toybox.SensorHistory has :getHeartRateHistory)){iterator=SensorHistory.getHeartRateHistory({:period=>1,:order=>SensorHistory.ORDER_NEWEST_FIRST});}else if(kind==:stress&&(Toybox.SensorHistory has :getStressHistory)){iterator=SensorHistory.getStressHistory({:period=>1,:order=>SensorHistory.ORDER_NEWEST_FIRST});}else if(kind==:bodyBattery&&(Toybox.SensorHistory has :getBodyBatteryHistory)){iterator=SensorHistory.getBodyBatteryHistory({:period=>1,:order=>SensorHistory.ORDER_NEWEST_FIRST});}else if(kind==:oxygen&&(Toybox.SensorHistory has :getOxygenSaturationHistory)){iterator=SensorHistory.getOxygenSaturationHistory({:period=>1,:order=>SensorHistory.ORDER_NEWEST_FIRST});}else if(kind==:pressure&&(Toybox.SensorHistory has :getPressureHistory)){iterator=SensorHistory.getPressureHistory({:period=>1,:order=>SensorHistory.ORDER_NEWEST_FIRST});}else if(kind==:temperature&&(Toybox.SensorHistory has :getTemperatureHistory)){iterator=SensorHistory.getTemperatureHistory({:period=>1,:order=>SensorHistory.ORDER_NEWEST_FIRST});}if(iterator==null){return null;}var sample=iterator.next();return sample==null?null:sample.data;}
}
