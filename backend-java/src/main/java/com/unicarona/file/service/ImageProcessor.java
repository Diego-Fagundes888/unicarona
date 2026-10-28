package com.unicarona.file.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.luciad.imageio.webp.CompressionType;
import com.luciad.imageio.webp.WebPWriteParam;
import com.twelvemonkeys.image.ResampleOp;
import com.unicarona.shared.ApiException;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.springframework.stereotype.Component;

@Component
public class ImageProcessor {
    public record Prepared(byte[] data,String contentType) {
        @Override public String toString() { return "Prepared[redacted]"; }
    }
    public Prepared prepare(String purpose,byte[] source) {
        ApiException.require(source.length>0 && source.length<=5*1024*1024,"FILE_SIZE","Arquivo vazio ou maior que 5 MB.",422);
        String type=sniff(source,purpose);
        ApiException.require(type!=null,"FILE_TYPE","Use PNG, JPG, WebP ou PDF para documentos.",422);
        if("application/pdf".equals(type)) return new Prepared(source,type);
        if("photo".equals(purpose)) ApiException.require(source.length<=2*1024*1024,"FILE_SIZE","Foto deve ter até 2 MB.",422);
        try(var stream=new MemoryCacheImageInputStream(new ByteArrayInputStream(source))) {
            var readers=ImageIO.getImageReadersByMIMEType(type);
            if(!readers.hasNext()) throw new IOException("Image reader unavailable.");
            ImageReader reader=readers.next(); var warning=new AtomicBoolean(); reader.addIIOReadWarningListener((r,message) -> warning.set(true));
            try {
                reader.setInput(stream);int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1 || height<1 || (long)width*height>16000000L) throw new IOException("Image dimensions exceed the limit.");
                reader.getImageMetadata(0);
                if(!"photo".equals(purpose)) return new Prepared(source,type);
                BufferedImage decoded=reader.read(0);
                if(decoded==null || warning.get()) throw new IOException("Image decoding failed.");
                BufferedImage image=orient(decoded,orientation(source));
                double scale=Math.min(1d,512d/Math.max(image.getWidth(),image.getHeight()));
                if(scale<1) image=new ResampleOp(Math.max(1,(int)Math.round(image.getWidth()*scale)),Math.max(1,(int)Math.round(image.getHeight()*scale)),ResampleOp.FILTER_LANCZOS).filter(image,null);
                return new Prepared(webp(image),"image/webp");
            } finally { reader.dispose(); }
        } catch(ApiException error) { throw error; }
        catch(IOException | RuntimeException error) { throw new ApiException("INVALID_IMAGE","Não foi possível ler a imagem.",422); }
    }
    private String sniff(byte[] bytes,String purpose) {
        if(bytes.length>=8 && Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10})) return "image/png";
        if(bytes.length>=3 && bytes[0]==(byte)255 && bytes[1]==(byte)216 && bytes[2]==(byte)255) return "image/jpeg";
        if(bytes.length>=12 && new String(bytes,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF") && new String(bytes,8,4,java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP")) return "image/webp";
        if(bytes.length>=5 && new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-") && !"photo".equals(purpose)) return "application/pdf";
        return null;
    }
    private int orientation(byte[] source) {
        try {
            var exif=ImageMetadataReader.readMetadata(new ByteArrayInputStream(source)).getFirstDirectoryOfType(ExifIFD0Directory.class);
            return exif!=null && exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION)?exif.getInt(ExifIFD0Directory.TAG_ORIENTATION):1;
        } catch(Exception ignored) { return 1; }
    }
    static BufferedImage orient(BufferedImage source,int orientation) {
        int w=source.getWidth(),h=source.getHeight(); boolean swap=orientation>=5 && orientation<=8;
        var output=new BufferedImage(swap?h:w,swap?w:h,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
            int dx=x,dy=y;
            switch(orientation) {
                case 2 -> dx=w-1-x;
                case 3 -> { dx=w-1-x;dy=h-1-y; }
                case 4 -> dy=h-1-y;
                case 5 -> { dx=y;dy=x; }
                case 6 -> { dx=h-1-y;dy=x; }
                case 7 -> { dx=h-1-y;dy=w-1-x; }
                case 8 -> { dx=y;dy=w-1-x; }
            }
            output.setRGB(dx,dy,source.getRGB(x,y));
        }
        return output;
    }
    private byte[] webp(BufferedImage image) throws IOException {
        var writers=ImageIO.getImageWritersByMIMEType("image/webp");if(!writers.hasNext()) throw new IOException("WebP writer unavailable.");
        var writer=writers.next();var bytes=new ByteArrayOutputStream();
        try(var stream=new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(stream);var options=(WebPWriteParam)writer.getDefaultWriteParam();options.setCompressionType(CompressionType.Lossy);
            options.setCompressionQuality(.85f);options.setMethod(4);writer.write(null,new IIOImage(image,null,null),options);stream.flush();return bytes.toByteArray();
        } finally { writer.dispose(); }
    }
}
