package org.gtlcore.gtlcore.client.ae2.graph;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.zip.*;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

public class StripPngWriterTest {
    static int checks;
    static void check(boolean ok, String reason) { checks++; if (!ok) throw new AssertionError(reason); }
    static void paint(Graphics2D g, int width, int height) {
        g.setColor(new Color(0xf2f1f5)); g.fillRect(0, 0, width, height);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x707078)); g.setStroke(new BasicStroke(1.2f));
        for (int y = -20; y < height; y+=37) {
            g.draw(new Line2D.Double(-5.3, y, width+0.4, y+height/2.3));
            g.draw(new Ellipse2D.Double(10.3, y-9.2, 39, 39));
            g.drawString("boundary 0123", 32, y);
        }
        BufferedImage icon = new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
        for (int y=0;y<32;y++) for (int x=0;x<32;x++) icon.setRGB(x,y, ((x+y)*4 << 24) | (x*8 << 16) | y*8);
        for (int y=242; y<height; y+=256) g.drawImage(icon, width/2, y, null);
    }
    static void checkRaster(Path directory) throws Exception {
        int w=777,h=769;
        var expected = new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        var g=expected.createGraphics();paint(g,w,h);g.dispose();
        Path file=directory.resolve("seams.png");
        Files.deleteIfExists(file);
        final int[] bands={0};
        StripPngWriter.write(file,w,h,(band,top,rows)->{bands[0]++;paint(band,w,h);});
        var actual=ImageIO.read(file.toFile());
        check(bands[0]==4,"four unequal strips");
        int mismatch=0,maxDifference=0;
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
            int a=actual.getRGB(x,y),b=expected.getRGB(x,y);
            if(a!=b) { mismatch++; for(int s=0;s<24;s+=8) maxDifference=Math.max(maxDifference,Math.abs((a>>>s &255)-(b>>>s &255))); }
        }
        System.out.println("Java2D strip comparison differing_pixels="+mismatch+" max_channel_difference="+maxDifference);
        check(mismatch==0,"strip render matches full raster");
        checkPng(file,w,h);
        expected.flush();actual.flush();

        var random = new BufferedImage(517, 529, BufferedImage.TYPE_INT_RGB);
        var rng=new Random(83819);
        for(int y=0;y<random.getHeight();y++) for(int x=0;x<random.getWidth();x++) random.setRGB(x,y,rng.nextInt());
        Path noise=directory.resolve("noise.png");Files.deleteIfExists(noise);
        StripPngWriter.write(noise,517,529,(band,top,rows)->band.drawImage(random,0,0,null));
        actual=ImageIO.read(noise.toFile());
        for(int y=0;y<529;y++) for(int x=0;x<517;x++) if(actual.getRGB(x,y)!=random.getRGB(x,y)) throw new AssertionError("lossy row filter");
        check(checkPng(noise,517,529)>1,"multiple IDAT chunks");
        actual.flush();random.flush();
    }
    static int checkPng(Path path,int width,int height) throws Exception {
        long inflated=0;int chunks=0;boolean ended=false;
        var inflater=new Inflater();byte[] decoded=new byte[64*1024];
        try(var in=new DataInputStream(Files.newInputStream(path))) {
            check(in.readLong()==0x89504e470d0a1a0aL,"signature");
            while(!ended) {
                int size=in.readInt();byte[] type=in.readNBytes(4),data=in.readNBytes(size);
                check(data.length==size,"complete chunk");var crc=new CRC32();crc.update(type);crc.update(data);
                check(in.readInt()==(int)crc.getValue(),"CRC");
                switch(new String(type,StandardCharsets.US_ASCII)) {
                    case "IHDR" -> {var head=new DataInputStream(new ByteArrayInputStream(data));check(head.readInt()==width&&head.readInt()==height,"full dimensions");}
                    case "IDAT" -> {chunks++;inflater.setInput(data);while(!inflater.needsInput()&&!inflater.finished()) {int n=inflater.inflate(decoded);inflated+=n;check(n>0||inflater.needsInput()||inflater.finished(),"zlib progress");}}
                    case "IEND" -> ended=true;
                    default -> throw new AssertionError("unexpected PNG chunk");
                }
            }
            check(inflater.finished(),"one complete zlib stream");
            check(inflated==((long)width*3+1)*height,"every scanline written");
            check(in.read()==-1,"no trailing bytes");
        } finally {inflater.end();}
        return chunks;
    }
    static void checkFailure(Path directory) throws Exception {
        Path file=directory.resolve("interrupted.png");Files.deleteIfExists(file);
        try {StripPngWriter.write(file,25,513,(g,top,rows)->{if(top>0)throw new IllegalStateException("expected");});throw new AssertionError("throw lost");} catch(IllegalStateException expected) {}
        check(!Files.exists(file),"failed output not exposed");
        try(var files=Files.list(directory)) {check(files.noneMatch(p->p.getFileName().toString().endsWith(".tmp")),"temporary file removed");}
        Path existing=directory.resolve("existing.png");Files.writeString(existing,"keep");
        try {StripPngWriter.write(existing,1,1,(g,top,rows)->{});throw new AssertionError("overwrote existing");} catch(FileAlreadyExistsException expected){}
        check(Files.readString(existing).equals("keep"),"existing export preserved");
        Thread.currentThread().interrupt();
        try {StripPngWriter.write(file,20,20,(g,top,rows)->{});throw new AssertionError("interrupt ignored");} catch(IOException expected){} finally {Thread.interrupted();}
        check(!Files.exists(file),"interruption cleanup");
        for(int[] size:new int[][]{{0,1},{1,0},{-1,1},{Integer.MAX_VALUE,1}}) {
            try {StripPngWriter.write(file,size[0],size[1],(g,top,rows)->{});throw new AssertionError("bad dimension accepted");} catch(IOException expected) {checks++;}
        }
    }
    record Node(double x,double y,BufferedImage icon,boolean missing,boolean seed,String label) {}
    static void replay(Path source,Path file) throws Exception {
        var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        var root=factory.newDocumentBuilder().parse(source.toFile()).getDocumentElement();
        String[] box=root.getAttribute("viewBox").split(" ");double left=Double.parseDouble(box[0]),top=Double.parseDouble(box[1]);
        int width=(int)Math.ceil(Double.parseDouble(box[2])*2),height=(int)Math.ceil(Double.parseDouble(box[3])*2);
        var icons=new HashMap<String,BufferedImage>();var images=root.getElementsByTagName("image");
        for(int i=0;i<images.getLength();i++){var e=(Element)images.item(i);var data=e.getAttribute("xlink:href");icons.put("#"+e.getAttribute("id"),ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(data.substring(data.indexOf(',')+1)))));}
        var lines=new ArrayList<Line2D.Double>();var paths=root.getElementsByTagName("path");
        for(int i=0;i<paths.getLength();i++){var d=((Element)paths.item(i)).getAttribute("d").substring(1).replace('L',' ').trim().split(" +");lines.add(new Line2D.Double(Double.parseDouble(d[0]),Double.parseDouble(d[1]),Double.parseDouble(d[2]),Double.parseDouble(d[3])));}
        var nodes=new ArrayList<Node>();var groups=root.getElementsByTagName("g");
        for(int i=0;i<groups.getLength();i++) {var e=(Element)groups.item(i);String transform=e.getAttribute("transform");if(transform.isEmpty())continue;
            var p=transform.substring(10,transform.length()-1).split(" ");var rect=(Element)e.getElementsByTagName("rect").item(0);var use=(Element)e.getElementsByTagName("use").item(0);var labels=e.getElementsByTagName("text");
            nodes.add(new Node(Double.parseDouble(p[0]),Double.parseDouble(p[1]),icons.get(use.getAttribute("xlink:href")),rect.getAttribute("fill").equals("#e7c4c4"),rect.getAttribute("stroke").equals("#168f99"),labels.getLength()==0?"":labels.item(0).getTextContent()));
        }
        Files.deleteIfExists(file);long start=System.nanoTime();
        StripPngWriter.write(file,width,height,(g,y,rows)->{
            g.setColor(new Color(0xf2f1f5));g.fillRect(0,y,width,rows);g.scale(2,2);g.translate(-left,-top);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setColor(new Color(0x505058));g.setStroke(new BasicStroke(.6f));
            double first=top+y/2.0-1,last=first+rows/2.0+2;
            for(var line:lines) if(Math.max(line.y1,line.y2)>=first&&Math.min(line.y1,line.y2)<=last)g.draw(line);
            g.setFont(new Font(Font.DIALOG,Font.PLAIN,5));
            for(var node:nodes) {if(node.y+24<first||node.y-24>last)continue;
                g.setColor(new Color(node.missing?0xe7c4c4:0xd5d4de));g.fill(new Rectangle2D.Double(node.x-11,node.y-11,22,22));
                g.setColor(new Color(node.seed?0x168f99:0x777580));g.draw(new Rectangle2D.Double(node.x-11,node.y-11,22,22));
                g.drawImage(node.icon,(int)node.x-8,(int)node.y-8,16,16,null);
                g.drawString(node.label,(float)node.x-g.getFontMetrics().stringWidth(node.label)/2f,(float)node.y+17);
            }
        });
        checkPng(file,width,height);
        System.out.printf("Real SVG: %d nodes, PNG %d x %d, %d bytes, %.2f s, max_heap=%d MiB%n",nodes.size(),width,height,Files.size(file),(System.nanoTime()-start)/1e9,Runtime.getRuntime().maxMemory()/1024/1024);
        // Region decode proves ordinary ImageIO readers can consume the giant single PNG.
        try(var input=ImageIO.createImageInputStream(file.toFile())) {
            var reader=ImageIO.getImageReaders(input).next();reader.setInput(input);var param=reader.getDefaultReadParam();
            param.setSourceRegion(new Rectangle(Math.max(0,width/2-300),Math.max(0,height/2-150),Math.min(width,600),Math.min(height,300)));
            var crop=reader.read(0,param);ImageIO.write(crop,"png",file.resolveSibling("hd-detail.png").toFile());crop.flush();reader.dispose();
        }
    }
    public static void main(String[] args) throws Exception {
        Path directory=Path.of(args[0]);Files.createDirectories(directory);checkRaster(directory);checkFailure(directory);
        if(args.length>1) replay(Path.of(args[1]),directory.resolve("real-graph-hd.png"));
        System.out.println("PASS: "+checks+" PNG checks");
    }
}
