package android.content;
public final class ComponentName {
 private final String pkg,name;
 public ComponentName(){this("dev.gyeopdam.embeddedtor.experiment","dev.gyeopdam.embeddedtor.TorProcessService");}
 public ComponentName(String pkg,String name){this.pkg=pkg;this.name=name;}
 public String getPackageName(){return pkg;}public String getClassName(){return name;}
}
