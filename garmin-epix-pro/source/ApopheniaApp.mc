using Toybox.Application;
using Toybox.Communications;
using Toybox.Lang;
using Toybox.WatchUi as Ui;
class ApopheniaApp extends Application.AppBase {
 function initialize(){AppBase.initialize();}
 function getInitialView(){Communications.registerForPhoneAppMessages(method(:onPhoneMessage));var view=makeMenu();ApopheniaDelivery.flush();return view;}
 function onPhoneMessage(message as Communications.PhoneAppMessage) as Void {var data=message.data;if(!(data instanceof Lang.Dictionary)||!"receipt".equals(data["type"])){return;}var ids=data["event_ids"];var removed=ApopheniaDelivery.acknowledge(ids);if(removed>0){ApopheniaState.setStatus("Saved on phone");}}
 function makeMenu(){var menu=new Ui.Menu2({:title=>"APOPHENIA"});ApopheniaState.statusItem=new Ui.MenuItem("THAT WAS WEIRD",ApopheniaState.status,"weird",null);menu.addItem(ApopheniaState.statusItem);menu.addItem(new Ui.MenuItem("Headache",null,"headache",null));menu.addItem(new Ui.MenuItem("Sinus / congestion",null,"sinus",null));menu.addItem(new Ui.MenuItem("Light changed",null,"light",null));menu.addItem(new Ui.MenuItem("Sound / noise",null,"sound",null));menu.addItem(new Ui.MenuItem("Body / sensation",null,"body",null));menu.addItem(new Ui.MenuItem("Coincidence",null,"coincidence",null));menu.addItem(new Ui.MenuItem("Hypothesis note",null,"hypothesis",null));menu.addItem(new Ui.MenuItem("Other observation",null,"other",null));menu.addItem(new Ui.MenuItem("Retry queued events",null,"retry",null));return [menu,new ApopheniaMenuDelegate()];}
}
module ApopheniaState{
 var status="Tap to log";
 var statusItem=null;
 function setStatus(text){status=text;if(statusItem!=null){statusItem.setSubLabel(text);}Ui.requestUpdate();}
}
