package com.unicarona.file.service;

import com.unicarona.shared.ApiException;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ImageProcessorTest {
    private final ImageProcessor processor=new ImageProcessor();
    static byte[] png(int width,int height) throws Exception {
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);var canvas=image.createGraphics();canvas.setColor(Color.BLUE);canvas.fillRect(0,0,width,height);canvas.dispose();
        var bytes=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,"png",bytes));return bytes.toByteArray();
    }
    @Test void photosAreRealWebpAtMost512WithoutEnlargementAndDocumentsKeepOriginalBytes() throws Exception {
        var source=png(1024,512);var photo=processor.prepare("photo",source);assertEquals("image/webp",photo.contentType());
        var decoded=ImageIO.read(new ByteArrayInputStream(photo.data()));assertNotNull(decoded);assertEquals(512,decoded.getWidth());assertEquals(256,decoded.getHeight());
        assertArrayEquals(photo.data(),processor.prepare("photo",source).data());
        assertArrayEquals(source,processor.prepare("identity",source).data());
        var small=ImageIO.read(new ByteArrayInputStream(processor.prepare("photo",png(20,10)).data()));assertEquals(20,small.getWidth());assertEquals(10,small.getHeight());
    }
    @Test void pdfIsAllowedOnlyForDocumentsAndInvalidMagicOrImagesFail() throws Exception {
        byte[] pdf="%PDF-1.7\nSynthetic test PDF\n%%EOF".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals("application/pdf",processor.prepare("identity",pdf).contentType());
        assertEquals("FILE_TYPE",assertThrows(ApiException.class,() -> processor.prepare("photo",pdf)).code());
        assertEquals("FILE_TYPE",assertThrows(ApiException.class,() -> processor.prepare("identity","<script>synthetic</script>".getBytes())).code());
        assertEquals("INVALID_IMAGE",assertThrows(ApiException.class,() -> processor.prepare("identity",new byte[]{(byte)137,80,78,71,13,10,26,10,0})).code());
        assertEquals("FILE_SIZE",assertThrows(ApiException.class,() -> processor.prepare("photo",new byte[0])).code());
    }
    @Test void sizeAndPixelLimitsAreCheckedBeforeExpensiveDecoding() throws Exception {
        byte[] source=png(1,1);byte[] oversized=Arrays.copyOf(source,2*1024*1024+1);
        assertEquals("FILE_SIZE",assertThrows(ApiException.class,() -> processor.prepare("photo",oversized)).code());
        byte[] bomb=source.clone();ByteBuffer.wrap(bomb).putInt(16,5000).putInt(20,5000);var crc=new CRC32();crc.update(bomb,12,17);ByteBuffer.wrap(bomb).putInt(29,(int)crc.getValue());
        assertEquals("INVALID_IMAGE",assertThrows(ApiException.class,() -> processor.prepare("photo",bomb)).code());
    }
    @Test void allEightExifOrientationsPreservePixelPositions() {
        var source=new BufferedImage(2,3,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<3;y++) for(int x=0;x<2;x++) source.setRGB(x,y,0xff000000|(1+x+2*y));
        int[][] pixels={{1,2,3,4,5,6},{2,1,4,3,6,5},{6,5,4,3,2,1},{5,6,3,4,1,2},{1,3,5,2,4,6},{5,3,1,6,4,2},{6,4,2,5,3,1},{2,4,6,1,3,5}};
        for(int o=1;o<=8;o++) {
            var oriented=ImageProcessor.orient(source,o);assertEquals(o>=5?3:2,oriented.getWidth());assertEquals(o>=5?2:3,oriented.getHeight());
            int[] values=new int[6];int index=0;for(int y=0;y<oriented.getHeight();y++) for(int x=0;x<oriented.getWidth();x++) values[index++]=oriented.getRGB(x,y)&0xff;
            assertArrayEquals(pixels[o-1],values);
        }
    }
    @Test void realJpegExifOrientationIsAppliedAndRemovedFromThePhoto() throws Exception {
        var image=new BufferedImage(32,16,BufferedImage.TYPE_INT_RGB);var raw=new ByteArrayOutputStream();ImageIO.write(image,"jpeg",raw);byte[] jpeg=raw.toByteArray();
        var exif=ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);exif.put(new byte[]{'E','x','i','f',0,0,'I','I',42,0,8,0,0,0});
        exif.putShort((short)1).putShort((short)0x112).putShort((short)3).putInt(1).putShort((short)6).putShort((short)0).putInt(0);
        // Preserve the JFIF APP0 marker immediately after SOI; EXIF follows that segment.
        int insertion=4+((jpeg[4]&255)<<8)+(jpeg[5]&255);
        var source=new ByteArrayOutputStream();source.write(jpeg,0,insertion);source.write(new byte[]{(byte)255,(byte)225,0,34});source.write(exif.array());source.write(jpeg,insertion,jpeg.length-insertion);
        var photo=processor.prepare("photo",source.toByteArray());var decoded=ImageIO.read(new ByteArrayInputStream(photo.data()));assertEquals(16,decoded.getWidth());assertEquals(32,decoded.getHeight());
        assertNull(com.drew.imaging.ImageMetadataReader.readMetadata(new ByteArrayInputStream(photo.data())).getFirstDirectoryOfType(com.drew.metadata.exif.ExifIFD0Directory.class));
    }
}
