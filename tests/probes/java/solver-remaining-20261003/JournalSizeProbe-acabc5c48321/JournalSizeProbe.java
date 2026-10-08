package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.lang.reflect.*;
public final class JournalSizeProbe {
 static int assertions, verified;
 static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
 static BigInteger b(long n){return BigInteger.valueOf(n);}
 static CountProof.Row row(long a,long rhs){return new CountProof.Row(Map.of(0,b(a)),b(rhs));}
 static long bytes(CountProof.Journal j)throws Exception{var f=CountProof.Journal.class.getDeclaredField("bytes");f.setAccessible(true);return f.getLong(j);}
 static int count(CountProof.Journal j){return j.entries().size()+j.executions().size()+j.divisibility().size()+j.rounding().size()+j.cliques().size()+j.derivations().size()+j.knapsacks().size()+j.diagrams().size()+j.symmetries().size()+j.inductions().size();}
 static void add(CountProof.Journal j,Object p)throws Exception{var m=CountProof.Journal.class.getDeclaredMethod("add",p.getClass());m.setAccessible(true);m.invoke(j,p);}
 static CountProof.Derivation derivation(BigInteger multiplier){return new CountProof.Derivation("derivation",1,List.of(row(1,1)),List.of(new CountProof.Combination(Map.of(0,multiplier),multiplier,row(1,1))));}
 static void verify(Object p)throws Exception{
  CountProof.Verdict v;if(p instanceof ExecutionProof.Certificate e)v=ExecutionProof.verify(e,1000000);else if(p instanceof CountInduction.Proof i)v=CountInduction.verify(i,1000000,x->{});else v=(CountProof.Verdict)CountProof.class.getMethod("verify",p.getClass(),long.class).invoke(null,p,1000000L);
  check(v==CountProof.Verdict.VERIFIED,"invalid sample "+p.getClass()+" "+v);verified++;
 }
 static CountInduction.Proof induction()throws Exception{
  var p=new CountInduction.Problem(List.of(b(0)),List.of(b(1)),List.of(List.of(b(1))),List.of(List.of(b(1))));
  var encode=CountInduction.class.getDeclaredMethod("encode",CountInduction.Problem.class,int.class,boolean.class,java.util.function.LongConsumer.class);encode.setAccessible(true);
  var certificates=new ArrayList<CountProof.Certificate>();
  for(boolean base:new boolean[]{true,false}){var e=encode.invoke(null,p,1,base,(java.util.function.LongConsumer)x->{});var scope=e.getClass().getDeclaredMethod("scope");scope.setAccessible(true);var low=e.getClass().getDeclaredMethod("low");low.setAccessible(true);int variables=((BigInteger[])low.invoke(e)).length;@SuppressWarnings("unchecked")var axioms=(List<CountProof.Row>)scope.invoke(e);certificates.add(new CountProof.Certificate("induction",variables,axioms,List.of(),List.of(),true));}
  return new CountInduction.Proof(p,1,certificates.get(0),certificates.get(1));
 }
 static List<Object> samples()throws Exception{
  var one=List.of(b(1));var zero=List.of(b(0));var bounds=List.of(new CountProof.Row(Map.of(0,b(-1)),b(0)),new CountProof.Row(Map.of(1,b(-1)),b(0)),new CountProof.Row(Map.of(0,b(1)),b(1)),new CountProof.Row(Map.of(1,b(1)),b(1)),new CountProof.Row(Map.of(0,b(1),1,b(1)),b(1)));
  return List.of(
   new CountProof.Certificate("closed",1,List.of(row(1,-1)),List.of(),List.of(),true),
   derivation(BigInteger.ONE),
   new CountProof.Divisibility("parity",1,List.of(row(2,1),row(-2,-1)),List.of(b(1),b(0))),
   new CountProof.Rounding("round",1,List.of(row(2,3),row(-1,0)),List.of(b(1),b(0)),b(2),zero,row(1,1)),
   new CountProof.Clique("clique",2,bounds,List.of(b(0),b(0)),List.of(b(1),b(1)),List.of(1,3),List.of(4),bounds.get(4)),
   new CountProof.Knapsack("cover",2,new CountProof.Row(Map.of(0,b(2),1,b(2)),b(3)),List.of(b(0),b(0)),List.of(b(1),b(1)),bounds.get(4)),
   new CountProof.Diagram("diagram",1,List.of(row(1,-1)),zero,one,List.of(0),List.of(List.of(List.of(b(-1))),List.of())),
   new CountProof.Symmetry("symmetry",2,bounds.subList(0,4),List.of(List.of(1,0)),List.of(new CountProof.Row(Map.of(0,b(1),1,b(-1)),b(0)))),
   new ExecutionProof.Certificate("execution",ExecutionProof.Kind.FORWARD_BOUNDARY,zero,one,List.of(),List.of(),List.of(zero),Set.of()),
   induction());
 }
 static void exactCaps(Path out)throws Exception{
  var samples=samples();for(Object sample:samples){verify(sample);var generous=new CountProof.Journal(10000000);add(generous,sample);long cost=bytes(generous);check(cost>0,"no cost");var below=new CountProof.Journal(cost-1);add(below,sample);check(count(below)==0&&below.truncated()&&bytes(below)==0,"below cap did not decline");var exact=new CountProof.Journal(cost);add(exact,sample);check(count(exact)==1&&!exact.truncated()&&bytes(exact)==cost,"exact cap did not retain");add(exact,sample);check(count(exact)==1&&exact.truncated()&&bytes(exact)==cost,"failed add changed records/ledger");
   Path file=out.resolve(sample.getClass().getSimpleName()+".cgpc");exact.write(file);var read=CountProof.read(file);check(count(read)==1&&read.truncated(),"roundtrip truncation");try(var channel=FileChannel.open(file,StandardOpenOption.WRITE)){check(channel.tryLock()!=null,"writer/reader leaked file handle");}
  }
  var combined=new CountProof.Journal(1000000);for(Object sample:samples)add(combined,sample);var file=out.resolve("all.cgpc");combined.write(file);var restored=CountProof.read(file);check(count(restored)==10&&!restored.truncated(),"all types roundtrip");for(var p:restored.entries())verify(p);for(var p:restored.derivations())verify(p);for(var p:restored.divisibility())verify(p);for(var p:restored.rounding())verify(p);for(var p:restored.cliques())verify(p);for(var p:restored.knapsacks())verify(p);for(var p:restored.diagrams())verify(p);for(var p:restored.symmetries())verify(p);for(var p:restored.executions())verify(p);for(var p:restored.inductions())verify(p);
 }
 static void huge()throws Exception{
  BigInteger m=BigInteger.ONE.shiftLeft(524000);var large=derivation(m);verify(large);var journal=new CountProof.Journal(1024);journal.add(large);check(journal.truncated()&&journal.derivations().isEmpty(),"original counterexample retained");
  var candidate=new CountProof.Journal(2000000);candidate.add(large);check(bytes(candidate)>2L*(m.bitLength()/8),"multiplier/divisor payload omitted");
  var scopes=new CountProof.Journal(1000);scopes.add(new CountProof.Certificate("S".repeat(20000),0,List.of(),List.of(),List.of(),false));check(scopes.truncated()&&scopes.entries().isEmpty(),"scope omitted");
  var examples=List.<Object>of(
   new CountProof.Certificate("fraction",1,List.of(row(1,1)),List.of(),List.of(new CountProof.Fraction(m,m)),false),
   new CountProof.Rounding("divisor",1,List.of(row(1,1)),List.of(b(1)),m,List.of(b(0)),row(1,1)),
   new CountProof.Knapsack("bounds",1,row(1,1),List.of(m),List.of(m),row(1,1)),
   new CountProof.Diagram("state",1,List.of(row(1,1)),List.of(b(0)),List.of(b(1)),List.of(0),List.of(List.of(List.of(m)))),
   new ExecutionProof.Certificate("state",ExecutionProof.Kind.FORWARD_BOUNDARY,List.of(b(0)),List.of(b(1)),List.of(),List.of(),List.of(List.of(m)),Set.of()),
   new CountProof.Clique("bounds",1,List.of(),List.of(m),List.of(m),List.of(0,1),List.of(0),row(1,1)),
   new CountProof.Divisibility("multiplier",1,List.of(row(1,1)),List.of(m)));
  for(Object example:examples){var j=new CountProof.Journal(8192);add(j,example);check(j.truncated()&&count(j)==0,"large payload omitted "+example.getClass());}
 }
 static void saturation()throws Exception{var size=Class.forName(CountProof.class.getName()+"$ArchiveSize");var add=size.getDeclaredMethod("add",long.class,long.class);add.setAccessible(true);var mul=size.getDeclaredMethod("multiply",long.class,long.class);mul.setAccessible(true);check((long)add.invoke(null,Long.MAX_VALUE-4,5L)==Long.MAX_VALUE,"addition overflow");check((long)add.invoke(null,3L,4L)==7,"addition exact");check((long)mul.invoke(null,Long.MAX_VALUE,2L)==Long.MAX_VALUE,"multiplication overflow");check((long)mul.invoke(null,65536L,65536L)==4294967296L,"int intermediate overflow");var retain=CountProof.Journal.class.getDeclaredMethod("retain",long.class);retain.setAccessible(true);var j=new CountProof.Journal(Long.MAX_VALUE);check(!(boolean)retain.invoke(j,Long.MAX_VALUE)&&j.truncated()&&bytes(j)==0,"saturation admitted");}
 public static void main(String[] args)throws Exception{var out=Path.of(args[0]);exactCaps(out);huge();saturation();System.out.println("assertions="+assertions+" independentlyVerified="+verified+" types=10 originalCounterexample=declined saturated=declined recordsPreserved=true handlesClosed=true");}
}
