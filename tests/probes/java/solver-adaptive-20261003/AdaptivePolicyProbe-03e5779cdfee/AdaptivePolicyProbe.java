package org.cgse.core;

import java.util.*;

public final class AdaptivePolicyProbe {
    static long assertions,decisions;
    static void require(boolean value){assertions++;if(!value)throw new AssertionError("check "+assertions);}
    public static void main(String[] args)throws Exception{
        for(int seed=0;seed<100;seed++)scaledReplay(seed);
        fairness();extremes();costResponse();completedOnly();
        System.out.println("PASS adaptive policy assertions="+assertions+" scaled decisions="+decisions);
    }
    static void scaledReplay(int seed){
        var a=new CountPortfolioPolicy();var b=new CountPortfolioPolicy();
        var left=new ArrayList<CountPortfolioPolicy.Arm>();var right=new ArrayList<CountPortfolioPolicy.Arm>();
        Random random=new Random(seed);int n=2+random.nextInt(30);long[] scale=new long[n];
        for(int i=0;i<n;i++){long startup=random.nextInt(100000);left.add(a.add(startup));right.add(b.add(startup));scale[i]=1+random.nextInt(1000000000);}
        for(int turn=0;turn<3000;turn++){
            var x=a.select();var y=b.select();int id=left.indexOf(x);require(id==right.indexOf(y));
            long remaining=turn%13==0?random.nextInt(4097):100000;
            long q=a.quantum(x,remaining);require(q==b.quantum(y,remaining));require(q>=0&&q<=remaining&&q<=32768);
            a.selected(x);b.selected(y);
            long progress=random.nextInt(5)==0?0:random.nextInt(10000),spent=turn%17==0?0:q+random.nextInt(30000);
            a.feedback(x,spent,progress);b.feedback(y,spent,Math.multiplyExact(progress,scale[id]));
            require(Double.doubleToLongBits(x.reward)==Double.doubleToLongBits(y.reward));
            require(Double.doubleToLongBits(x.costMean)==Double.doubleToLongBits(y.costMean));
            require(Double.doubleToLongBits(x.costM2)==Double.doubleToLongBits(y.costM2));
            require(Double.doubleToLongBits(x.progressCostMean)==Double.doubleToLongBits(y.progressCostMean));
            require(x.work==y.work&&x.observations==y.observations&&x.waiting==y.waiting);
            require(Double.isFinite(x.reward)&&Double.isFinite(x.costMean)&&Double.isFinite(x.costM2));
            decisions++;
        }
    }
    static void fairness()throws Exception{
        for(boolean overflow:new boolean[]{false,true}){
            var p=new CountPortfolioPolicy();var arms=new ArrayList<CountPortfolioPolicy.Arm>();
            for(int i=0;i<12;i++)arms.add(p.add(11-i));
            if(overflow){var f=CountPortfolioPolicy.class.getDeclaredField("turn");f.setAccessible(true);f.setLong(p,Long.MAX_VALUE-1);}
            for(int i=0;i<12;i++){var a=p.select();require(a==arms.get(11-i));p.selected(a);p.feedback(a,4096,i==11?999:0);}
            long[] last=new long[12];Arrays.fill(last,11);
            for(int turn=12;turn<1000;turn++){
                var a=p.select();int id=arms.indexOf(a);require(turn-last[id]<=36);last[id]=turn;
                p.selected(a);p.feedback(a,id==0?32768:4096,id==0?Long.MAX_VALUE:0);
            }
            arms.get(0).retired=true;for(int t=0;t<100;t++){var a=p.select();require(a!=arms.get(0));p.selected(a);p.feedback(a,4096,0);}
            for(var a:arms)a.retired=true;require(p.select()==null);
        }
    }
    static void extremes(){
        var p=new CountPortfolioPolicy();var a=p.add(Long.MAX_VALUE);p.add(0);
        a.observations=Long.MAX_VALUE;a.progressObservations=Long.MAX_VALUE;a.selections=Integer.MAX_VALUE;
        for(int i=0;i<100;i++){
            p.selected(a);p.feedback(a,i%3==0?0:Long.MAX_VALUE,i%2==0?Long.MAX_VALUE:0);
            require(a.work>=0&&a.observations==Long.MAX_VALUE&&a.selections==Integer.MAX_VALUE);
            require(Double.isFinite(a.costMean)&&Double.isFinite(a.costM2)&&Double.isFinite(a.progressCostMean));
            require(p.quantum(a,Long.MAX_VALUE)>=4096&&p.quantum(a,Long.MAX_VALUE)<=32768);
        }
    }
    static void costResponse(){
        var p=new CountPortfolioPolicy();var cheap=p.add(0);var costly=p.add(0);
        for(int i=0;i<4;i++){p.selected(cheap);p.feedback(cheap,4096,4096);p.selected(costly);p.feedback(costly,16384,16384);}
        require(p.quantum(costly,100000)>p.quantum(cheap,100000));
        long productive=p.quantum(costly,100000);
        for(int i=0;i<8;i++){p.selected(costly);p.feedback(costly,4096,0);}
        require(p.quantum(costly,100000)<productive&&costly.idleSlices>=2);
        require(p.quantum(costly,0)==0&&p.quantum(costly,7)==7);
        cheap.retired=true;require(p.quantum(costly,100000)==32768);
    }
    static void completedOnly(){
        var p=new CountPortfolioPolicy();var a=p.add(1);p.add(2);
        p.selected(a);p.feedback(a,4096,1);
        long observations=a.observations,work=a.work;double cost=a.costMean,reward=a.reward;
        p.selected(a);for(int i=0;i<100;i++)p.quantum(a,100000); // paused/incomplete/cancelled slice has no feedback.
        require(a.observations==observations&&a.work==work&&a.costMean==cost&&a.reward==reward);
        try{p.feedback(a,-1,99);throw new AssertionError();}catch(IllegalArgumentException expected){}
        require(a.observations==observations&&a.work==work&&a.costMean==cost&&a.reward==reward);
        p.feedback(a,32768,8);require(a.observations==observations+1&&a.work==work+32768);
    }
}
