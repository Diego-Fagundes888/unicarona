package com.unicarona.notification.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** Local mailbox only; one private JSON file per stable outbox ID. */
public final class DevelopmentEmailProvider implements EmailProvider {
    private final Path root,publicRoot;
    private final ObjectMapper json;
    public DevelopmentEmailProvider(Path root,Path publicRoot,ObjectMapper json) {
        this.root=root.toAbsolutePath().normalize();this.publicRoot=publicRoot.toAbsolutePath().normalize();this.json=json;
        assertPrivate(this.root,this.publicRoot);
    }
    private static void assertPrivate(Path actual,Path publicPath) {
        if(actual.startsWith(publicPath)) throw new IllegalStateException("The development mailbox must be outside the public frontend.");
    }
    @Override public void send(Message input) throws IOException {
        boolean posix=FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        if(posix) Files.createDirectories(root,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));else Files.createDirectories(root);
        Path actual=root.toRealPath();assertPrivate(actual,Files.exists(publicRoot)?publicRoot.toRealPath():publicRoot);
        Path target=actual.resolve(input.id()+".json");
        byte[] bytes=json.writerWithDefaultPrettyPrinter().writeValueAsBytes(input);
        var options=Set.of(StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING,LinkOption.NOFOLLOW_LINKS);
        try(var channel=posix?Files.newByteChannel(target,options,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))):Files.newByteChannel(target,options)) {
            var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining()) channel.write(buffer);
        }
    }
}
