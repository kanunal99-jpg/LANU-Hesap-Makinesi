package com.example.core.messaging;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Line transport for synthetic smoke only. State/key data never goes to CI stdout. */
public final class OlmRatchetNative {
    private native String execute(String request, byte[] key);
    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        var engine=new OlmRatchetNative();
        var input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.US_ASCII));
        for(String line;(line=input.readLine())!=null;) {
            byte[] key=null;
            try {
                String[] parts=line.split(" ",2);
                key=Base64.getDecoder().decode(parts[0]);
                String request=new String(Base64.getDecoder().decode(parts[1]),StandardCharsets.UTF_8);
                String response=engine.execute(request,key);
                System.out.println(Base64.getEncoder().encodeToString(response.getBytes(StandardCharsets.UTF_8)));
            } catch(Exception error) {
                System.out.println("ERROR");
            } finally {if(key!=null) Arrays.fill(key,(byte)0);}
        }
    }
}
