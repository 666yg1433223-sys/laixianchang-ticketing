package com.ticketing.booking.demo;

import com.ticketing.booking.config.Bootstrap;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DemoClasspathOfflineTest {
    @Test void exportsOnlyFreshTestClasspathWhenExplicitlyRequested() throws Exception {
        String classpath=exportClasspath(System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")));
        for(String item:classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            Path p=Path.of(item).toAbsolutePath().normalize();
            assertTrue(Files.exists(p));
            assertTrue(p.equals(Bootstrap.ROOT.resolve("target/classes")) || p.equals(Bootstrap.ROOT.resolve("target/test-classes"))
                || p.startsWith(Bootstrap.ROOT.resolve(".m2")),"Unexpected classpath origin");
        }
        String destination=System.getProperty("demo.classpathFile");
        if(destination==null) return;
        Path path=Path.of(destination).toAbsolutePath().normalize();
        assertEquals("classpath.txt",path.getFileName().toString());
        assertEquals(DemoFiles.directory(path.getParent().getFileName().toString()),path.getParent());
        DemoFiles.writeBytes(path,classpath.getBytes(StandardCharsets.UTF_8));
    }
    // Surefire appends a separator; omit only its trailing empty entries, never a middle entry.
    static String exportClasspath(String raw) {
        if(raw==null) throw new IllegalArgumentException("Missing classpath");
        var entries=raw.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator),-1);
        int length=entries.length;
        while(length>0 && entries[length-1].isEmpty()) length--;
        if(length==0) throw new IllegalArgumentException("Empty classpath");
        for(int i=0;i<length;i++) if(entries[i].isBlank()) throw new IllegalArgumentException("Empty classpath entry");
        return String.join(java.io.File.pathSeparator,java.util.Arrays.copyOf(entries,length));
    }
    @Test void exportsNoImplicitWorkingDirectoryEntry() {
        String sep=java.io.File.pathSeparator;
        assertEquals("first"+sep+"second",exportClasspath("first"+sep+"second"+sep));
        assertThrows(IllegalArgumentException.class,()->exportClasspath("first"+sep+sep+"second"));
        assertThrows(IllegalArgumentException.class,()->exportClasspath(sep));
    }
}
