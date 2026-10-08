package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Local prototype: numerical proposals with exact finite-domain rejection. */
final class CountFastBoolean implements AutoCloseable {
    private record Row(int[] ids, long[] a, long b) {}
    private final PlanningBudget budget;
    private final List<ExactLinearProgram.Constraint> original;
    private final BigInteger[] lower,upper;
    private final List<Row> rows=new ArrayList<>();
    private final Deque<byte[]> pending=new ArrayDeque<>();
    private final int n;
    private final double[] costs;
    private long memory, nodes, certified, numerics, failedCertificates;
    private boolean complete,infeasible;
    private BigInteger[] counts;
    CountFastBoolean(List<ExactLinearProgram.Constraint> input,BigInteger[] low,BigInteger[] high,PlanningBudget budget){
        this.budget=budget;original=input;lower=low;upper=high;n=low.length;costs=new double[n];
        if(n>128||input.size()>1024){complete=true;return;}
        for(int i=0;i<n;i++)if(high[i]==null||high[i].subtract(low[i]).compareTo(BigInteger.ONE)>0){complete=true;return;}
        long terms=input.stream().mapToLong(r->r.terms().size()).sum();
        long bytes=8192+64L*n*n+256L*terms+128L*input.size();
        if(!budget.tryReserve(bytes)){complete=true;return;}memory=bytes;
        try {
            byte[] initial=new byte[n];Arrays.fill(initial,(byte)-1);for(int i=0;i<n;i++)if(low[i].equals(high[i]))initial[i]=0;
            int costSize=0;
            for(var source:input){
                BigInteger b=source.upper();var map=new TreeMap<Integer,BigInteger>();
                for(var t:source.terms().entrySet()){budget.check();b=b.subtract(t.getValue().multiply(low[t.getKey()]));if(initial[t.getKey()]<0)map.put(t.getKey(),t.getValue());}
                var normalized=CountReduction.normalize(new ExactLinearProgram.Constraint(map,b));
                BigInteger min=BigInteger.ZERO,max=BigInteger.ZERO;for(var v:normalized.terms().values()){min=min.add(v.min(BigInteger.ZERO));max=max.add(v.max(BigInteger.ZERO));}
                if(normalized.upper().compareTo(min)<0){complete=infeasible=true;return;}
                if(normalized.upper().compareTo(max)>=0)continue;
                if(min.abs().add(max.abs()).bitLength()>60){complete=true;return;}
                int[] ids=new int[normalized.terms().size()];long[] a=new long[ids.length];int k=0;
                for(var t:normalized.terms().entrySet()){ids[k]=t.getKey();a[k++]=t.getValue().longValueExact();}
                rows.add(new Row(ids,a,normalized.upper().longValueExact()));
                if(ids.length>costSize&&normalized.terms().values().stream().allMatch(x->x.signum()>0)){
                    Arrays.fill(costs,0);for(int j=0;j<ids.length;j++)costs[ids[j]]=a[j];costSize=ids.length;
                }
            }
            pending.add(initial);
        } catch(RuntimeException|Error e){close();throw e;}
    }
    boolean step(){
        if(complete)return true;
        budget.check();if(pending.isEmpty()){complete=infeasible=true;return true;}
        byte[] x=pending.removeLast();nodes++;
        if(!propagate(x))return false;
        int free=0;for(byte v:x)if(v<0)free++;
        if(free==0){accept(x);return complete;}
        int[] ids=new int[free],remap=new int[n];Arrays.fill(remap,-1);int k=0;
        for(int i=0;i<n;i++)if(x[i]<0){ids[k]=i;remap[i]=k++;}
        List<ExactLinearProgram.Constraint> lpRows=new ArrayList<>();
        for(Row row:rows){long b=row.b,min=0,max=0;Map<Integer,BigInteger> map=new LinkedHashMap<>();
            for(int j=0;j<row.ids.length;j++){budget.check();int id=row.ids[j];long a=row.a[j];if(x[id]<0){min+=Math.min(0,a);max+=Math.max(0,a);map.put(remap[id],BigInteger.valueOf(a));}else b-=a*x[id];}
            if(b<min)throw new AssertionError("missed propagation");if(b>=max)continue;
            lpRows.add(new ExactLinearProgram.Constraint(map,BigInteger.valueOf(b)));
        }
        for(int i=0;i<free;i++)lpRows.add(new ExactLinearProgram.Constraint(Map.of(i,BigInteger.ONE),BigInteger.ONE));
        BigInteger[] objective=new BigInteger[free];for(int i=0;i<free;i++)objective[i]=BigInteger.valueOf(-(long)costs[ids[i]]);
        var proposal=CountNumericRelaxation.solve(free,lpRows,objective,budget,200000);numerics++;
        if(proposal!=null&&proposal.phaseOneInfeasible()){
            if(certify(lpRows,free,proposal.dual())){certified++;return false;}failedCertificates++;
        }
        double[] point=proposal==null||proposal.point()==null?new double[free]:proposal.point();
        byte[] rounded=x.clone();for(int i=0;i<free;i++)rounded[ids[i]]=(byte)(point[i]>=.5?1:0);
        if(valid(rounded)){accept(rounded);return true;}
        int chosen=0;double best=-1;
        for(int i=0;i<free;i++){double p=point[i];double score=Math.min(p,1-p);if(!Double.isFinite(score))score=0;
            if(score>best){best=score;chosen=i;}}
        int id=ids[chosen];byte value=(byte)(point[chosen]>=.5?1:0);
        byte[] other=x.clone();other[id]=(byte)(1-value);x[id]=value;pending.addLast(other);pending.addLast(x);
        return false;
    }
    private boolean propagate(byte[] x){boolean changed;do{changed=false;
        for(Row row:rows){long min=0;for(int j=0;j<row.ids.length;j++){budget.check();byte v=x[row.ids[j]];min+=v<0?Math.min(0,row.a[j]):row.a[j]*v;}
            long slack=row.b-min;if(slack<0)return false;
            for(int j=0;j<row.ids.length;j++){budget.check();int id=row.ids[j];if(x[id]>=0)continue;long a=row.a[j];if(Math.abs(a)>slack){x[id]=(byte)(a>0?0:1);changed=true;}}
        }
    }while(changed);return true;}
    private boolean certify(List<ExactLinearProgram.Constraint> scope,int size,double[] dual){
        if(dual==null||dual.length!=scope.size())return false;double largest=0;for(double v:dual){if(!Double.isFinite(v))return false;largest=Math.max(largest,v);}if(largest<=0)return false;
        for(int bits:new int[]{16,24,32,40}){
            BigInteger[] a=new BigInteger[size];Arrays.fill(a,BigInteger.ZERO);BigInteger b=BigInteger.ZERO;
            for(int i=0;i<scope.size();i++){budget.check();long rounded=Math.round(Math.max(0,dual[i])/largest*(1L<<bits));if(rounded==0)continue;
                BigInteger w=BigInteger.valueOf(rounded);var row=scope.get(i);b=b.add(w.multiply(row.upper()));
                for(var t:row.terms().entrySet()){budget.check();int id=t.getKey();a[id]=a[id].add(w.multiply(t.getValue()));}
            }
            BigInteger min=BigInteger.ZERO;for(BigInteger v:a)min=min.add(v.min(BigInteger.ZERO));if(b.compareTo(min)<0)return true;
        }return false;
    }
    private boolean valid(byte[] x){for(Row row:rows){long total=0;for(int i=0;i<row.ids.length;i++){budget.check();total+=row.a[i]*x[row.ids[i]];}if(total>row.b)return false;}return true;}
    private void accept(byte[] x){
        BigInteger[] result=new BigInteger[n];for(int i=0;i<n;i++)result[i]=lower[i].add(BigInteger.valueOf(x[i]));
        for(var row:original){BigInteger sum=BigInteger.ZERO;for(var t:row.terms().entrySet()){budget.check();sum=sum.add(t.getValue().multiply(result[t.getKey()]));}if(sum.compareTo(row.upper())>0)throw new AssertionError("invalid result");}
        counts=result;complete=true;
    }
    BigInteger[] counts(){return counts;}
    boolean infeasible(){return infeasible;}
    long nodes(){return nodes;}
    @Override public void close(){budget.note("fast_boolean", "nodes="+nodes+"; LP="+numerics+"; certified="+certified+"; failed_certificates="+failedCertificates);budget.release(memory);memory=0;}
}
