using Toybox.Application;
using Toybox.WatchUi as Ui;
class ApopheniaApp extends Application.AppBase {
 function initialize(){AppBase.initialize();}
 function getInitialView(){return makeMenu();}
 function makeMenu(){var menu=new Ui.Menu2({:title=>"APOPHENIA"});menu.addItem(new Ui.MenuItem("THAT WAS WEIRD",ApopheniaState.status,"weird",null));menu.addItem(new Ui.MenuItem("Headache",null,"headache",null));menu.addItem(new Ui.MenuItem("Sinus / congestion",null,"sinus",null));menu.addItem(new Ui.MenuItem("Light changed",null,"light",null));menu.addItem(new Ui.MenuItem("Sound / noise",null,"sound",null));menu.addItem(new Ui.MenuItem("Body / sensation",null,"body",null));menu.addItem(new Ui.MenuItem("Coincidence",null,"coincidence",null));menu.addItem(new Ui.MenuItem("Hypothesis note",null,"hypothesis",null));menu.addItem(new Ui.MenuItem("Other observation",null,"other",null));return [menu,new ApopheniaMenuDelegate()];}
}
module ApopheniaState{var status="Tap to log";}
