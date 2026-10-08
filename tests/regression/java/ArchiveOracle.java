package org.cgse.core;
import java.math.*;import java.util.*;import java.nio.file.*;
public class ArchiveOracle {
 static final BigInteger Z=BigInteger.ZERO,O=BigInteger.ONE,T=BigInteger.TWO;
 public static void main(String[] args)throws Exception {
Files.createDirectories(Path.of("build/test-artifacts/algorithm-review"));
 var source=new CountProof.Row(Map.of(0,T),BigInteger.valueOf(3));
 var derived=new CountProof.Derivation("derive",1,List.of(source),List.of(new CountProof.Combination(Map.of(0,O),T,new CountProof.Row(Map.of(0,O),O))));
 var knapsack=new CountProof.Knapsack("lift",3,new CountProof.Row(Map.of(0,T,1,T,2,T),BigInteger.valueOf(3)),List.of(Z,Z,Z),List.of(O,O,O),new CountProof.Row(Map.of(0,O,1,O,2,O),O));
 var diagram=new CountProof.Diagram("diagram",1,List.of(new CountProof.Row(Map.of(0,O.negate()),O.negate()),new CountProof.Row(Map.of(0,O),Z)),List.of(Z),List.of(O),List.of(0),List.of(List.of(List.of(O.negate(),Z)),List.of()));
 var symmetry=new CountProof.Symmetry("symmetry",2,List.of(new CountProof.Row(Map.of(0,O,1,O),O)),List.of(List.of(1,0)),List.of(new CountProof.Row(Map.of(0,O,1,O.negate()),Z)));
 for(int version=6;version<=9;version++){var j=new CountProof.Journal(1<<20);j.add(derived);if(version>=7)j.add(knapsack);if(version>=8)j.add(diagram);if(version>=9)j.add(symmetry);var file=Path.of("build/test-artifacts/algorithm-review/proofs-v"+version+".cgp");j.write(file);var k=CountProof.read(file);if(!j.derivations().equals(k.derivations())||!j.knapsacks().equals(k.knapsacks())||!j.diagrams().equals(k.diagrams())||!j.symmetries().equals(k.symmetries()))throw new AssertionError("roundtrip");CountProof.main(new String[]{file.toString()});}
 var bad=new CountProof.Derivation("bad",1,List.of(source),List.of(new CountProof.Combination(Map.of(0,O),T,new CountProof.Row(Map.of(0,O),Z))));if(CountProof.verify(bad,100000)!=CountProof.Verdict.INVALID)throw new AssertionError("invalid rounding accepted");
 if(CountProof.verify(new CountProof.Knapsack("bad",3,knapsack.source(),knapsack.lower(),knapsack.upper(),new CountProof.Row(knapsack.consequence().terms(),Z)),100000)!=CountProof.Verdict.INVALID)throw new AssertionError("invalid lift accepted");
 var invalidDiagram=new CountProof.Diagram("bad",1,List.of(new CountProof.Row(Map.of(0,O),O)),List.of(Z),List.of(O),List.of(0),List.of(List.of(List.of(O)),List.of()));if(CountProof.verify(invalidDiagram,100000)!=CountProof.Verdict.INVALID)throw new AssertionError("missing successor accepted");
 if(CountProof.verify(new CountProof.Symmetry("bad",2,symmetry.axioms(),symmetry.permutations(),List.of(new CountProof.Row(symmetry.leaders().get(0).terms(),O.negate()))),100000)!=CountProof.Verdict.INVALID)throw new AssertionError("invalid lex accepted");
 var j=new CountProof.Journal(1);j.add(derived);if(!j.truncated())throw new AssertionError("truncation lost");System.out.println("PASS CGP6/7/8/9 round-trip, independent replay, forged consequence/closure/lex rejection and truncation");
 }
}
