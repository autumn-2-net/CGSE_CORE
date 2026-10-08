import java.net.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
public class CompareCurrent {
 record Engine(URLClassLoader loader,Class<?> fixtures,Class<?> fixture,Method run) {
  Object build(String kind,int n)throws Exception {Method f=fixtures.getDeclaredMethod(kind,kind.equals("cycle")?new Class<?>[]{long.class}:kind.equals("sat")?new Class<?>[]{int.class,int.class,int.class}:new Class<?>[]{int.class,int.class});f.setAccessible(true);return kind.equals("cycle")?f.invoke(null,(long)n):kind.equals("sat")?f.invoke(null,n,n*4,42):f.invoke(null,n,42);}
  Object solve(Object f)throws Exception{return run.invoke(null,f);}
 }
 static Engine engine(Path classes)throws Exception {
  var urls=new ArrayList<URL>();urls.add(classes.toUri().toURL());urls.add(Path.of(".local/cp-sat-recheck-20260926/classes").toUri().toURL());
  try(var files=Files.list(Path.of(".local/cp-sat-repro-20260925/windows-jars"))){for(Path file:files.filter(p->p.toString().endsWith(".jar")).toList())urls.add(file.toUri().toURL());}
  var loader=new URLClassLoader(urls.toArray(URL[]::new),ClassLoader.getPlatformClassLoader());var fixtures=Class.forName("org.cgse.core.ContrastProbe",true,loader);var fixture=Class.forName(fixtures.getName()+"$Fixture",true,loader);var probe=Class.forName("org.cgse.core.RecheckProbe",true,loader);var run=probe.getDeclaredMethod("runGtl",fixture);run.setAccessible(true);return new Engine(loader,fixtures,fixture,run);
 }
 static Object get(Object record,String field)throws Exception{var method=record.getClass().getDeclaredMethod(field);method.setAccessible(true);return method.invoke(record);}
 public static void main(String[]args)throws Exception{
  var old=engine(Path.of(".local/count-order-review-20260926/before-classes"));var fresh=engine(Path.of(".local/count-order-review-20260926/classes"));
  for(String spec:List.of("subset:20","subset:28","subset:36","sat:12","sat:20","sat:32","sat:64","cycle:1000000000")){
   String[] p=spec.split(":");Object a=old.build(p[0],Integer.parseInt(p[1])),b=fresh.build(p[0],Integer.parseInt(p[1]));
   for(int i=0;i<64;i++){if(i%2==0){old.solve(a);fresh.solve(b);}else{fresh.solve(b);old.solve(a);}}
   double[] ot=new double[101],nt=new double[101];for(int i=0;i<101;i++){Object or,nr;if(i%2==0){or=old.solve(a);nr=fresh.solve(b);}else{nr=fresh.solve(b);or=old.solve(a);}ot[i]=(double)get(or,"ms");nt[i]=(double)get(nr,"ms");if(!get(or,"status").toString().startsWith("FEASIBLE")||!get(nr,"status").toString().startsWith("FEASIBLE"))throw new AssertionError("Unexpected status "+spec+" "+get(nr,"info"));if(i==0){System.out.println("DETAIL old "+spec+" "+get(or,"info"));System.out.println("DETAIL new "+spec+" "+get(nr,"info"));}}
   Arrays.sort(ot);Arrays.sort(nt);System.out.printf(Locale.ROOT,"MEDIAN %s old_ms=%.6f new_ms=%.6f ratio=%.3f samples=101%n",spec,ot[50],nt[50],nt[50]/ot[50]);
  }
 }
}
