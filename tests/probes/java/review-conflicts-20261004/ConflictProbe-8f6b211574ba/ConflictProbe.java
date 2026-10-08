package org.cgse.core;

import com.google.gson.Gson;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class ConflictProbe {
    static long checks, transfers, learned, guarded, oracleFacts, resumed;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static BigInteger bi(long v){return BigInteger.valueOf(v);}
    static PlanningBudget budget(){return new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);}
    static ExactLinearProgram.Constraint row(int id,boolean minimum,BigInteger value){return new ExactLinearProgram.Constraint(Map.of(id,bi(minimum?-1:1)),minimum?value.negate():value);}
    static boolean violates(CountConflict c,BigInteger[] point){return StrideProbe.accepts(c.assumptions(),point);}
    static CountViewConflicts pool(CountModelViews views)throws Exception{var field=CountModelViews.class.getDeclaredField("conflicts");field.setAccessible(true);return (CountViewConflicts)field.get(views);}
    static List<CountConflict> facts(CountModelViews views,CountModelViews.View view)throws Exception{var p=pool(views);var out=new ArrayList<CountConflict>();if(p!=null)p.transfer(view,0,new Object(),c->{out.add(c);return true;});return out;}
    static void installView(CountModelViews models,CountModelViews.View view)throws Exception{var field=CountModelViews.class.getDeclaredField("views");field.setAccessible(true);@SuppressWarnings("unchecked")var list=(List<CountModelViews.View>)field.get(models);list.add(view);}
    static void run(CountLcg search){int slices=0;do{while(!search.step()){}if(!search.paused())break;search.resume(16384);}while(++slices<64);}
    static void clausesPreserve(List<CountConflict> clauses,Set<List<BigInteger>> expected,String label){for(var point:expected)for(var c:clauses)check(!violates(c,point.toArray(BigInteger[]::new)),label+" excluded "+point+" by "+c);}

    static void randomModels()throws Exception{
        for(int seed=0;seed<1200;seed++){
            var rng=new Random(104729L*seed+3);int n=2+seed%3;var lo=new BigInteger[n];var hi=new BigInteger[n];var witness=new BigInteger[n];
            for(int i=0;i<n;i++){lo[i]=bi(seed%5==0?rng.nextInt(4)-2:0);hi[i]=lo[i].add(bi(seed%4==0?1:2+rng.nextInt(3)));witness[i]=lo[i].add(bi(rng.nextInt(hi[i].subtract(lo[i]).intValue()+1)));}
            var rows=new ArrayList<ExactLinearProgram.Constraint>();
            for(int r=0;r<2+seed%7;r++){var terms=new LinkedHashMap<Integer,BigInteger>();var rhs=bi(0);for(int i=0;i<n;i++){var a=bi(rng.nextInt(19)-9);if(a.signum()!=0)terms.put(i,a);rhs=rhs.add(a.multiply(witness[i]));}if(r==0&&seed%3==0)StrideProbe.equation(rows,terms,rhs);else rows.add(new ExactLinearProgram.Constraint(terms,rhs.add(bi(rng.nextInt(5)-(seed%7==0?2:0)))));}
            var expected=StrideProbe.enumerate(rows,lo,hi,p->p);var b=budget();
            try(var models=CountModelViews.create(rows,lo,hi,b);var reduction=new CountReduction(rows,lo,hi,b)){
                reduction.retainStrideView();while(!reduction.step()){}models.compileLight();models.addReduced(reduction);
                for(var view:models.available()){
                    var local=StrideProbe.enumerate(view.rows(),view.lower(),view.upper(),p->p);
                    var rootLow=view.lower().clone();var rootHigh=view.upper().clone();
                    if(seed%2==0&&rootLow.length>0){int id=seed%rootLow.length;rootHigh[id]=rootLow[id].add(rootHigh[id].subtract(rootLow[id]).divide(bi(2)));guarded++;}
                    if(seed%11==0&&rootLow.length>1){int id=(seed+1)%rootLow.length;rootLow[id]=rootLow[id].add(rootHigh[id].subtract(rootLow[id]).divide(bi(2)));}
                    var scope=new CountModelViews.Domains(rootLow,rootHigh,0);
                    try(var s=new CountLcg(view.rows(),rootLow,rootHigh,b,16384)){
                        run(s);var values=s.learnedConflicts();learned+=values.size();models.publishConflicts(view,scope,values,0,s);
                    }
                    boolean binary=true;for(int i=0;i<rootLow.length;i++)if(rootHigh[i].subtract(rootLow[i]).compareTo(bi(1))>0)binary=false;
                    if(binary)try(var pb=new CountCdcl(view.rows(),rootLow,rootHigh,b,32768).retained()){
                        int rounds=0;do{while(!pb.step()){}if(!pb.paused())break;pb.resume(32768);}while(++rounds<10);
                        var values=pb.learnedConflicts();learned+=values.size();models.publishConflicts(view,scope,values,0,pb);
                    }
                    // Exclude infeasible points by an independent bounded oracle, then
                    // test every original solution, including points outside the guard.
                    for(int attempt=0;attempt<8;attempt++){
                        var p=new BigInteger[rootLow.length];var assumptions=new ArrayList<ExactLinearProgram.Constraint>();
                        for(int i=0;i<p.length;i++){p[i]=rootLow[i].add(bi(rng.nextInt(rootHigh[i].subtract(rootLow[i]).intValue()+1)));assumptions.add(row(i,true,p[i]));assumptions.add(row(i,false,p[i]));}
                        if(!StrideProbe.accepts(view.rows(),p)){models.publishConflicts(view,scope,List.of(new CountConflict(assumptions)),0,new Object());oracleFacts++;}
                    }
                    // A scoped empty nogood is legal only when that whole subdomain is closed.
                    var scoped=StrideProbe.enumerate(view.rows(),rootLow,rootHigh,p->p);
                    if(scoped.isEmpty())models.publishConflicts(view,scope,List.of(new CountConflict(List.of())),0,new Object());
                }
                for(var view:models.available()){
                    var values=facts(models,view);transfers+=values.size();var local=StrideProbe.enumerate(view.rows(),view.lower(),view.upper(),p->p);clausesPreserve(values,local,"seed="+seed+" view="+view.name());
                    try(var s=new CountLcg(view.rows(),view.lower(),view.upper(),b,16384)){
                        models.importConflicts(view,0,s,s);run(s);check(s.infeasible()==local.isEmpty(),"wrong conclusion "+seed+" "+view.name()+" trace="+b.diagnostics());check(s.infeasible()||s.counts()!=null,"unfinished tiny solve");
                        if(s.counts()!=null){check(local.contains(List.of(s.counts())),"bad local witness");check(expected.contains(List.of(models.restoreAndCheck(view,s.counts()))),"bad original witness");}
                        clausesPreserve(s.learnedConflicts(),local,"derived after sharing");
                    }
                }
                // A different inventory/target/force/seed/excluded scope is a different
                // owner even when dimensions, rows and bounds happen to compare equal.
                try(var foreign=CountModelViews.create(rows,lo,hi,b)){
                    var view=foreign.available().get(0);int old=models.conflictVersion();models.publishConflicts(view,new CountModelViews.Domains(lo,hi,0),List.of(new CountConflict(List.of())),0,new Object());check(models.conflictVersion()==old,"foreign scope entered");check(foreign.conflictVersion()==0,"facts persisted to another request");
                }
            }
            check(b.reservedBytes()==0,"random leak "+seed+": "+b.reservedBytes());
        }
        check(learned>50&&transfers>1000&&oracleFacts>1000,"sharing was not exercised");
    }

    static void affineAndGuards()throws Exception{
        // Exhaustive arithmetic transfer on signed/non-unit/permuted coordinates.
        // The expected domain is the exact image; no dense-domain approximation is used.
        for(int seed=0;seed<96;seed++){
            int n=3;var rng=new Random(seed);int[] permutation={0,1,2};for(int i=2;i>0;i--){int j=rng.nextInt(i+1),v=permutation[i];permutation[i]=permutation[j];permutation[j]=v;}
            var expressions=new ArrayList<CountMapping.Expression>();var low=new BigInteger[]{bi(-2),bi(-1),bi(0)};var high=new BigInteger[]{bi(3),bi(3),bi(4)};
            for(int i=0;i<n;i++)expressions.add(new CountMapping.Expression(Map.of(permutation[i],bi((seed+i)%2==0?i+2:-i-2)),bi(seed%7-3)));
            var mapping=new CountMapping(expressions);var rows=List.of(new ExactLinearProgram.Constraint(Map.of(0,bi(-1),1,bi(-1)),bi(-2)));
            var view=new CountModelViews.View("affine",rows,low,high,new CountModelViews.Shape(3,2,2,3),p->{var out=new BigInteger[3];for(int i=0;i<3;i++){var e=expressions.get(i);var t=e.terms().entrySet().iterator().next();out[i]=e.constant().add(t.getValue().multiply(p[t.getKey()]));}return out;},CountModelViews.Semantics.EQUIVALENT,mapping);
            var b=budget();try(var p=CountViewConflicts.create(b)){
                var scopedHigh=high.clone();scopedHigh[0]=bi(0);var clause=new CountConflict(List.of(row(1,false,bi(1))));p.publish(view,low,scopedHigh,List.of(clause),0,new Object());check(p.version()==1,"affine export failed");
                var original=new CountModelViews.View("original",List.of(),new BigInteger[3],new BigInteger[3],new CountModelViews.Shape(3,0,1,3),null,CountModelViews.Semantics.EQUIVALENT,null);
                var originals=new ArrayList<CountConflict>();p.transfer(original,0,new Object(),c->{originals.add(c);return true;});check(originals.size()==1,"missing lifted clause");
                var local=new ArrayList<CountConflict>();p.transfer(view,0,new Object(),c->{local.add(c);return true;});check(local.size()==1,"missing pulled clause");
                var box=StrideProbe.enumerate(List.of(),low,high,x->x);
                for(var point:box){var x=point.toArray(BigInteger[]::new);boolean should=x[0].compareTo(bi(0))<=0&&x[1].compareTo(bi(1))<=0;check(violates(originals.get(0),view.restore(x))==should,"signed affine lift changed guard");check(violates(local.get(0),x)==should,"signed affine round trip");}
            }check(b.reservedBytes()==0,"affine leak");
        }
        // A branch-only fact must disappear when its guard is retracted.
        var rows=List.of(new ExactLinearProgram.Constraint(Map.of(0,bi(-1),1,bi(-1)),bi(-1)));var low=new BigInteger[]{bi(0),bi(0)};var high=new BigInteger[]{bi(1),bi(1)};var b=budget();
        try(var models=CountModelViews.create(rows,low,high,b)){
            var view=models.available().get(0);models.publishConflicts(view,new CountModelViews.Domains(low,new BigInteger[]{bi(0),bi(1)},0),List.of(new CountConflict(List.of(row(1,false,bi(0))))),0,new Object());
            var values=facts(models,view);check(values.size()==1,"missing guarded fact");check(!violates(values.get(0),new BigInteger[]{bi(1),bi(0)}),"guard removed");
            for(var semantics:List.of(CountModelViews.Semantics.RESTRICTED,CountModelViews.Semantics.HINT)){
                var restricted=new CountModelViews.View("restricted",List.of(),low,high,view.shape(),null,semantics,null);installView(models,restricted);int before=models.conflictVersion();models.publishConflicts(restricted,new CountModelViews.Domains(low,high,0),List.of(new CountConflict(List.of())),0,new Object());check(models.conflictVersion()==before,"restricted negative entered");
            }
        }check(b.reservedBytes()==0,"guard leak");
    }

    static void pausedAndProofs()throws Exception{
        var level=CountLcg.class.getDeclaredField("level");level.setAccessible(true);var allowance=CountLcg.class.getDeclaredField("allowance");allowance.setAccessible(true);var work=CountLcg.class.getDeclaredField("work");work.setAccessible(true);
        for(int seed=0;seed<80;seed++){
            int n=5+seed%5;var lo=new BigInteger[n];var hi=new BigInteger[n];Arrays.fill(lo,bi(0));Arrays.fill(hi,bi(1));var terms=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<n;i++)terms.put(i,bi(-1));var rows=List.of(new ExactLinearProgram.Constraint(terms,bi(-2)));
            var oracle=StrideProbe.enumerate(rows,lo,hi,x->x);var b=budget();try(var s=new CountLcg(rows,lo,hi,b,16384)){
                int iterations=0;while(level.getInt(s)==0&&!s.step()&&++iterations<10000){}check(level.getInt(s)>0,"no decision");
                allowance.setLong(s,work.getLong(s));check(s.step()&&s.paused(),"pause failed");
                var assumed=new ArrayList<ExactLinearProgram.Constraint>();for(int i=0;i<n;i++)assumed.add(row(i,false,bi(i==n-1?1:0)));var c=new CountConflict(assumed);clausesPreserve(List.of(c),oracle,"input nogood");
                s.resume(16384);check(s.learn(c),"live trail import refused");check(!s.learn(c),"duplicate import");run(s);check(!s.infeasible()&&s.counts()!=null&&oracle.contains(List.of(s.counts())),"live import bad result");clausesPreserve(s.learnedConflicts(),oracle,"live import derived");resumed++;
            }check(b.reservedBytes()==0,"pause leak");
        }
        for(int seed=0;seed<24;seed++){
            var lo=new BigInteger[]{bi(0),bi(0)};var hi=new BigInteger[]{bi(2),bi(2)};var rows=new ArrayList<ExactLinearProgram.Constraint>();StrideProbe.equation(rows,Map.of(0,bi(2),1,bi(2)),bi(1+seed%3));var b=budget();b.proofJournal(new CountProof.Journal(1L<<20));
            try(var models=CountModelViews.create(rows,lo,hi,b);var s=new CountLcg(rows,lo,hi,b,65536)){
                check(!s.learn(new CountConflict(List.of())) ,"unproved import entered journal");run(s);var view=models.available().get(0);models.publishConflicts(view,new CountModelViews.Domains(lo,hi,0),s.learnedConflicts(),0,s);check(models.conflictVersion()==0,"journal silently trusted cross-view axiom");
                for(var certificate:b.proofJournal().entries())check(CountProof.verify(certificate,1_000_000)==CountProof.Verdict.VERIFIED,"invalid journal");
            }check(b.reservedBytes()==0,"proof leak");
        }
        // Imported original facts must also constrain a covering relaxation's candidates.
        var b=budget();try(var s=new CountLcg(List.of(),new BigInteger[]{bi(0),bi(0)},new BigInteger[]{bi(1),bi(1)},b,16384)){
            s.learn(new CountConflict(List.of(row(0,false,bi(0)),row(1,false,bi(0)))));run(s);check(!Arrays.equals(s.counts(),new BigInteger[]{bi(0),bi(0)}),"relaxation accepted forbidden low point");
        }check(b.reservedBytes()==0,"relaxation leak");
    }

    static void lifecycle()throws Exception{
        for(int mode=0;mode<3;mode++)for(int limit=1;limit<=160;limit++){
            int cap=limit*7;var calls=new AtomicInteger();var b=new PlanningBudget(0,mode==2?cap:2_000_000,mode==1?limit*1024L:32L<<20,()->modeValue.get()==0&&calls.incrementAndGet()>=cap,System::nanoTime);modeValue.set(mode);
            var rows=new ArrayList<ExactLinearProgram.Constraint>();StrideProbe.equation(rows,Map.of(0,bi(6),1,bi(10)),bi(100));var lo=new BigInteger[]{bi(0),bi(0)};var hi=new BigInteger[]{bi(20),bi(20)};
            try(var models=CountModelViews.create(rows,lo,hi,b);var reduction=new CountReduction(rows,lo,hi,b)){
                reduction.retainStrideView();while(!reduction.step()){}if(models==null)continue;models.compileLight();models.addReduced(reduction);
                for(var view:models.available()){
                    try(var s=new CountLcg(view.rows(),view.lower(),view.upper(),b,4096)){
                        run(s);models.publishConflicts(view,new CountModelViews.Domains(view.lower(),view.upper(),0),s.learnedConflicts(),0,s);
                    }
                    var assumptions=new ArrayList<ExactLinearProgram.Constraint>();for(int i=0;i<view.lower().length;i++)assumptions.add(row(i,false,view.lower()[i]));models.publishConflicts(view,new CountModelViews.Domains(view.lower(),view.upper(),0),List.of(new CountConflict(assumptions)),0,new Object());
                }
                for(var view:models.available())try(var s=new CountLcg(view.rows(),view.lower(),view.upper(),b,4096)){models.importConflicts(view,0,s,s);run(s);}
            }catch(java.util.concurrent.CancellationException|PlanningBudget.Exhausted good){}
            check(b.reservedBytes()==0,"lifecycle leak mode="+mode+" limit="+limit+" bytes="+b.reservedBytes());
        }
    }
    static final ThreadLocal<Integer> modeValue=ThreadLocal.withInitial(()->-1);
    public static void main(String[]args)throws Exception{randomModels();affineAndGuards();pausedAndProofs();lifecycle();var report=Map.of("random_models",1200,"affine_cases",96,"checks",checks,"transferred_clauses",transfers,"learned_clauses",learned,"oracle_clauses",oracleFacts,"guarded_sources",guarded,"retained_imports",resumed,"proof_cases",24,"lifecycle",480);Files.writeString(Path.of(args[0],"conflict-probe.json"),new Gson().toJson(report));System.out.println(report);}
}
