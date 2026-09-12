package com.ticketing.booking.demo;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.ticketing.booking.config.Bootstrap;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

public final class DemoFiles {
    public static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private DemoFiles() { }
    public static void createDirectory(Path path) throws Exception {
        Path expected=Bootstrap.ROOT.resolve(".local/demo");
        Path absolute=path.toAbsolutePath().normalize();
        if(!absolute.equals(expected) && !(absolute.getParent().equals(expected) && absolute.getFileName().toString().matches("[a-z0-9-]{8,24}")))
            throw new IllegalArgumentException("INVALID_DEMO_DIRECTORY");
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)) {requirePrivate(path);return;}
        if(!path.getParent().toRealPath().equals(path.getParent().toAbsolutePath().normalize())) throw new IllegalStateException("LINKED_DEMO_PARENT");
        var current=path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        FileAttribute<?> attribute;
        if(Files.getFileStore(path.getParent()).supportsFileAttributeView("acl")) {
            var entry=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(current).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
            attribute=new FileAttribute<List<AclEntry>>() {public String name(){return "acl:acl";}public List<AclEntry> value(){return List.of(entry);} };
        } else attribute=PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
        Files.createDirectory(path,attribute);requirePrivate(path);
    }
    public static Path directory(String runId) throws Exception {
        if(!runId.matches("[a-z0-9-]{8,24}")) throw new IllegalArgumentException("INVALID_DEMO_RUN");
        Path root=Bootstrap.ROOT.resolve(".local/demo").resolve(runId).toAbsolutePath().normalize();
        requirePrivate(root);return root;
    }
    public static void requirePrivate(Path path) throws Exception {
        Path absolute=path.toAbsolutePath().normalize();
        if(!absolute.toRealPath().equals(absolute) || Files.isSymbolicLink(path)) throw new IllegalStateException("LINKED_DEMO_PATH");
        var basic=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!(basic.isDirectory()||basic.isRegularFile())) throw new IllegalStateException("UNSUPPORTED_DEMO_PATH");
        var current=path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        if(!Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).equals(current)) throw new IllegalStateException("DEMO_OWNER_MISMATCH");
        var acl=Files.getFileAttributeView(path,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(acl!=null) {
            for(var entry:acl.getAcl()) if(entry.type()==AclEntryType.ALLOW&&!entry.principal().equals(current))
                throw new IllegalStateException("DEMO_PERMISSIONS_TOO_BROAD");
        } else for(var p:Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS))
            if(p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_")) throw new IllegalStateException("DEMO_PERMISSIONS_TOO_BROAD");
    }
    public static JsonNode read(Path path) throws Exception {
        requirePrivate(path);
        if(Files.size(path)>1_048_576) throw new IllegalStateException("DEMO_FILE_TOO_LARGE");
        return JSON.readTree(Files.readString(path));
    }
    public static void write(Path path,Object value) throws Exception { writeBytes(path,JSON.writeValueAsBytes(value)); }
    public static void writeBytes(Path path,byte[] bytes) throws Exception {
        requirePrivate(path.getParent());
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)) requirePrivate(path);
        Path staged=path.resolveSibling("write-"+UUID.randomUUID()+".tmp");
        var current=path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        FileAttribute<?> attribute;
        if(Files.getFileStore(path.getParent()).supportsFileAttributeView("acl")) {
            var entry=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(current).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
            attribute=new FileAttribute<List<AclEntry>>() { public String name(){return "acl:acl";}public List<AclEntry> value(){return List.of(entry);} };
        } else attribute=PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
        try(var channel=Files.newByteChannel(staged,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE),attribute)) {
            ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining()) channel.write(buffer);
        }
        requirePrivate(staged);
        Files.move(staged,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
}
