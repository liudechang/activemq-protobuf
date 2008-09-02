/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.protobuf.compiler;

import static org.apache.activemq.protobuf.WireInfo.WIRETYPE_FIXED32;
import static org.apache.activemq.protobuf.WireInfo.WIRETYPE_FIXED64;
import static org.apache.activemq.protobuf.WireInfo.WIRETYPE_LENGTH_DELIMITED;
import static org.apache.activemq.protobuf.WireInfo.WIRETYPE_START_GROUP;
import static org.apache.activemq.protobuf.WireInfo.WIRETYPE_VARINT;
import static org.apache.activemq.protobuf.WireInfo.makeTag;

import com.google.protobuf.ByteString;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

import org.apache.activemq.protobuf.compiler.parser.ParseException;
import org.apache.activemq.protobuf.compiler.parser.ProtoParser;

public class JavaGenerator {

    private File out = new File(".");
    private File[] path = new File[]{new File(".")};

    private ProtoDescriptor proto;
    private String javaPackage;
    private String outerClassName;
    private PrintWriter w;
    private int indent;
    private String optimizeFor;
    private ArrayList<String> errors = new ArrayList<String>();
    private boolean multipleFiles;

    public static void main(String[] args) {
        
        JavaGenerator generator = new JavaGenerator();
        args = CommandLineSupport.setOptions(generator, args);
        
        if (args.length == 0) {
            System.out.println("No proto files specified.");
        }
        for (int i = 0; i < args.length; i++) {
            try {
                System.out.println("Compiling: "+args[i]);
                generator.compile(new File(args[i]));
            } catch (CompilerException e) {
                System.out.println("Protocol Buffer Compiler failed with the following error(s):");
                for (String error : e.getErrors() ) {
                    System.out.println("");
                    System.out.println(error);
                }
                System.out.println("");
                System.out.println("Compile failed.  For more details see error messages listed above.");
                return;
            }
        }

    }

    static public class CompilerException extends Exception {
        private final List<String> errors;

        public CompilerException(List<String> errors) {
            this.errors = errors;
        }

        public List<String> getErrors() {
            return errors;
        }
    }

    interface Closure {
        void execute() throws CompilerException;
    }
    
    public void compile(File file) throws CompilerException {

        // Parse the proto file
        FileInputStream is=null;
        try {
            is = new FileInputStream(file);
            ProtoParser parser = new ProtoParser(is);
            proto = parser.ProtoDescriptor();
            proto.setName(file.getName());
            loadImports(proto, file.getParentFile());
            proto.validate(errors);
        } catch (FileNotFoundException e) {
            errors.add("Failed to open: "+file.getPath()+":"+e.getMessage());
        } catch (ParseException e) {
            errors.add("Failed to parse: "+file.getPath()+":"+e.getMessage());
        } finally {
            try { is.close(); } catch (Throwable ignore){}
        }

        if (!errors.isEmpty()) {
            throw new CompilerException(errors);
        }

        // Load the options..
        javaPackage = javaPackage(proto);
        outerClassName = javaClassName(proto);
        optimizeFor = getOption(proto, "optimize_for", "SPEED");
        multipleFiles = isMultipleFilesEnabled(proto);
        
        if( multipleFiles ) {
            generateProtoFile();
        } else {
            writeFile(outerClassName, new Closure(){
                public void execute() throws CompilerException {
                    generateProtoFile();
                }
            });
        }
        
        if (!errors.isEmpty()) {
            throw new CompilerException(errors);
        }

    }

    private void writeFile(String className, Closure closure) throws CompilerException {
        PrintWriter oldWriter = w;
        // Figure out the java file name..
        File outputFile = out;
        if (javaPackage != null) {
            String packagePath = javaPackage.replace('.', '/');
            outputFile = new File(outputFile, packagePath);
        }
        outputFile = new File(outputFile, className + ".java");
        
        // Start writing the output file..
        outputFile.getParentFile().mkdirs();
        
        FileOutputStream fos=null;
        try {
            fos = new FileOutputStream(outputFile);
            w = new PrintWriter(fos);
            closure.execute();
            w.flush();
        } catch (FileNotFoundException e) {
            errors.add("Failed to write to: "+outputFile.getPath()+":"+e.getMessage());
        } finally {
            try { fos.close(); } catch (Throwable ignore){}
            w = oldWriter;
        }
    }

    private void loadImports(ProtoDescriptor proto, File protoDir) {
        LinkedHashMap<String,ProtoDescriptor> children = new LinkedHashMap<String,ProtoDescriptor>(); 
        for (String imp : proto.getImports()) {
            File file = new File(protoDir, imp);
            for (int i = 0; i < path.length && !file.exists(); i++) {
                file = new File(path[i], imp);
            } 
            if ( !file.exists() ) {
                errors.add("Cannot load import: "+imp);
            }
            
            FileInputStream is=null;
            try {
                is = new FileInputStream(file);
                ProtoParser parser = new ProtoParser(is);
                ProtoDescriptor child = parser.ProtoDescriptor();
                child.setName(file.getName());
                loadImports(child, file.getParentFile());
                children.put(imp, child);
            } catch (ParseException e) {
                errors.add("Failed to parse: "+file.getPath()+":"+e.getMessage());
            } catch (FileNotFoundException e) {
                errors.add("Failed to open: "+file.getPath()+":"+e.getMessage());
            } finally {
                try { is.close(); } catch (Throwable ignore){}
            }
        }
        proto.setImportProtoDescriptors(children);
    }


    private void generateProtoFile() throws CompilerException {
        if( multipleFiles ) {
            for (EnumDescriptor value : proto.getEnums().values()) {
                final EnumDescriptor o = value;
                String className = uCamel(o.getName());
                writeFile(className, new Closure(){
                    public void execute() throws CompilerException {
                        generateFileHeader();
                        generateEnum(o);
                    }
                });
            }
            for (MessageDescriptor value : proto.getMessages().values()) {
                final MessageDescriptor o = value;
                String className = uCamel(o.getName());
                writeFile(className, new Closure(){
                    public void execute() throws CompilerException {
                        generateFileHeader();
                        generateMessageBean(o);
                    }
                });
            }

        } else {
            generateFileHeader();

            p("public class " + outerClassName + " {");
            indent();

            for (EnumDescriptor enumType : proto.getEnums().values()) {
                generateEnum(enumType);
            }
            for (MessageDescriptor m : proto.getMessages().values()) {
                generateMessageBean(m);
            }

            unindent();
            p("}");
        }
    }

    private void generateFileHeader() {
        p("//");
        p("// Generated by protoc, do not edit by hand.");
        p("//");
        if (javaPackage != null) {
            p("package " + javaPackage + ";");
            p("");
        }
    }

    private void generateMessageBean(MessageDescriptor m) {
        
        String className = uCamel(m.getName());
        p();
        
        String staticOption = "static ";
        if( multipleFiles && m.getParent()==null ) {
            staticOption="";
        }
        
        
        p("public "+staticOption+"final class " + className + " extends org.apache.activemq.protobuf.Message<" + className + "> {");
        p();

        indent();
        
        for (EnumDescriptor enumType : m.getEnums().values()) {
            generateEnum(enumType);
        }

        // Generate the Nested Messages.
        for (MessageDescriptor subMessage : m.getMessages().values()) {
            generateMessageBean(subMessage);
        }

        // Generate the Group Messages
        for (FieldDescriptor field : m.getFields().values()) {
            if( field.isGroup() ) {
                generateMessageBean(field.getGroup());
            }
        }

        // Generate the field accessors..
        for (FieldDescriptor field : m.getFields().values()) {
            generateFieldAccessor(className, field);
        }
        
        generateMethodAssertInitialized(m, className);

        generateMethodClear(m);

        p("public "+className+" clone() {");
        p("   return new "+className+"().mergeFrom(this);");
        p("}");
        p();
        
        generateMethodMergeFromBean(m, className);

        generateMethodSerializedSize(m);
        
        generateMethodMergeFromStream(m, className);

        generateMethodWriteTo(m);

        generateMethodParseFrom(m, className);

        generateMethodToString(m);
                
        unindent();
        p("}");
        p();
    }
    
    private void generateMethodParseFrom(MessageDescriptor m, String className) {
        p("public static "+className+" parseFrom(com.google.protobuf.ByteString data) throws com.google.protobuf.InvalidProtocolBufferException {");
        indent();
        p("return new "+className+"().mergeFrom(data).checktInitialized();");
        unindent();
        p("}");
        p();

        p("public static "+className+" parseFrom(com.google.protobuf.ByteString data, com.google.protobuf.ExtensionRegistry extensionRegistry) throws com.google.protobuf.InvalidProtocolBufferException {");
        indent();
        p("return new "+className+"().mergeFrom(data, extensionRegistry).checktInitialized();");
        unindent();
        p("}");
        p();

        p("public static "+className+" parseFrom(byte[] data) throws com.google.protobuf.InvalidProtocolBufferException {");
        indent();
        p("return new "+className+"().mergeFrom(data).checktInitialized();");
        unindent();
        p("}");
        p();

        p("public static "+className+" parseFrom(byte[] data, com.google.protobuf.ExtensionRegistry extensionRegistry) throws com.google.protobuf.InvalidProtocolBufferException {");
        indent();
        p("return new "+className+"().mergeFrom(data,extensionRegistry).checktInitialized();");
        unindent();
        p("}");
        p();
        
        p("public static "+className+" parseFrom(java.io.InputStream data) throws com.google.protobuf.InvalidProtocolBufferException, java.io.IOException {");
        indent();
        p("return new "+className+"().mergeFrom(data).checktInitialized();");
        unindent();
        p("}");
        p();

        p("public static "+className+" parseFrom(java.io.InputStream data, com.google.protobuf.ExtensionRegistry extensionRegistry) throws com.google.protobuf.InvalidProtocolBufferException, java.io.IOException {");
        indent();
        p("return new "+className+"().mergeFrom(data,extensionRegistry).checktInitialized();");
        unindent();
        p("}");
        p();        
    }

    /**
     * @param m
     */
    private void generateMethodSerializedSize(MessageDescriptor m) {
        p("public int serializedSize() {");
        indent();
        p("if (memoizedSerializedSize != -1)");
        p("   return memoizedSerializedSize;");
        p();
        p("int size = 0;");
        for (FieldDescriptor field : m.getFields().values()) {
            
            String uname = uCamel(field.getName());
            String getter="get"+uname+"()";            
            String type = javaType(field);
            p("if (has"+uname+"()) {");
            indent();
            
            if( field.getRule() == FieldDescriptor.REPEATED_RULE ) {
                p("for ("+type+" i : get"+uname+"List()) {");
                indent();
                getter = "i";
            }

            if( field.getType()==FieldDescriptor.STRING_TYPE ) {
                p("size += com.google.protobuf.CodedOutputStream.computeStringSize("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.BYTES_TYPE ) {
                p("size += com.google.protobuf.CodedOutputStream.computeBytesSize("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.BOOL_TYPE ) {
                p("size += com.google.protobuf.CodedOutputStream.computeBoolSize("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.DOUBLE_TYPE ) {
                p("size += com.google.protobuf.CodedOutputStream.computeDoubleSize("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.FLOAT_TYPE ) {
                p("size += com.google.protobuf.CodedOutputStream.computeFloatSize("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.INT32_TYPE ) {
                p("size += com.google.protobuf.CodedOutputStream.computeInt32Size("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.INT64_TYPE ) {
                p("size += com.google.protobuf.CodedOutputStream.computeInt64Size("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.SINT32_TYPE ) {
                p("size += com.google.protobuf.CodedOutputStream.computeSInt32Size("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.SINT64_TYPE ) {
                p("size += com.google.protobuf.CodedOutputStream.computeSInt64Size("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.UINT32_TYPE ) {
                p("size += com.google.protobuf.CodedOutputStream.computeUInt32Size("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.UINT64_TYPE ) {
                p("size += com.google.protobuf.CodedOutputStream.computeUInt64Size("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.FIXED32_TYPE ) {
                p("size += com.google.protobuf.CodedOutputStream.computeFixed32Size("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.FIXED64_TYPE ) {
                p("size += com.google.protobuf.CodedOutputStream.computeFixed64Size("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.SFIXED32_TYPE ) {
                p("size += com.google.protobuf.CodedOutputStream.computeSFixed32Size("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.SFIXED64_TYPE ) {
                p("size += com.google.protobuf.CodedOutputStream.computeSFixed64Size("+field.getTag()+", "+getter+");");
            } else if( field.getTypeDescriptor().isEnum() ) {
                p("size += com.google.protobuf.CodedOutputStream.computeEnumSize("+field.getTag()+", "+getter+".getNumber());");
            } else if ( field.getGroup()!=null ) {
                p("size += computeGroupSize("+field.getTag()+", "+getter+");");
            } else {
                p("size += computeMessageSize("+field.getTag()+", "+getter+");");
            }
            if( field.getRule() == FieldDescriptor.REPEATED_RULE ) {
                unindent();
                p("}");
            }
            //TODO: finish this up.
            unindent();
            p("}");

        }
        // TODO: handle unknown fields
        // size += getUnknownFields().getSerializedSize();");
        p("memoizedSerializedSize = size;");
        p("return size;");
        unindent();
        p("}");
        p();
    }

    /**
     * @param m
     */
    private void generateMethodWriteTo(MessageDescriptor m) {
        p("public void writeTo(com.google.protobuf.CodedOutputStream output) throws java.io.IOException {");
        indent();
        for (FieldDescriptor field : m.getFields().values()) {
            String uname = uCamel(field.getName());
            String getter="get"+uname+"()";            
            String type = javaType(field);
            p("if (has"+uname+"()) {");
            indent();
            
            if( field.getRule() == FieldDescriptor.REPEATED_RULE ) {
                p("for ("+type+" i : get"+uname+"List()) {");
                indent();
                getter = "i";
            }

            if( field.getType()==FieldDescriptor.STRING_TYPE ) {
                p("output.writeString("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.BYTES_TYPE ) {
                p("output.writeBytes("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.BOOL_TYPE ) {
                p("output.writeBool("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.DOUBLE_TYPE ) {
                p("output.writeDouble("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.FLOAT_TYPE ) {
                p("output.writeFloat("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.INT32_TYPE ) {
                p("output.writeInt32("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.INT64_TYPE ) {
                p("output.writeInt64("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.SINT32_TYPE ) {
                p("output.writeSInt32("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.SINT64_TYPE ) {
                p("output.writeSInt64("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.UINT32_TYPE ) {
                p("output.writeUInt32("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.UINT64_TYPE ) {
                p("output.writeUInt64("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.FIXED32_TYPE ) {
                p("output.writeFixed32("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.FIXED64_TYPE ) {
                p("output.writeFixed64("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.SFIXED32_TYPE ) {
                p("output.writeSFixed32("+field.getTag()+", "+getter+");");
            } else if( field.getType()==FieldDescriptor.SFIXED64_TYPE ) {
                p("output.writeSFixed64("+field.getTag()+", "+getter+");");
            } else if( field.getTypeDescriptor().isEnum() ) {
                p("output.writeEnum("+field.getTag()+", "+getter+".getNumber());");
            } else if ( field.getGroup()!=null ) {
                p("writeGroup(output, "+field.getTag()+", "+getter+");");
            } else {
                p("writeMessage(output, "+field.getTag()+", "+getter+");");
            }
            
            if( field.getRule() == FieldDescriptor.REPEATED_RULE ) {
                unindent();
                p("}");
            }
            
            //TODO: finish this up.
            unindent();
            p("}");
        }
        // TODO: handle unknown fields
        // getUnknownFields().writeTo(output);
        unindent();
        p("}");
        p();        
    }

    /**
     * @param m
     * @param className
     */
    private void generateMethodMergeFromStream(MessageDescriptor m, String className) {
        p("public "+className+" mergeFrom(com.google.protobuf.CodedInputStream input, com.google.protobuf.ExtensionRegistry extensionRegistry) throws java.io.IOException {");
        indent(); {
          //TODO: handle unknown fields
          // UnknownFieldSet.Builder unknownFields = com.google.protobuf.UnknownFieldSet.newBuilder(this.unknownFields);
            
          p("while (true) {");
          indent(); {
              p("int tag = input.readTag();");
              // Is it an end group tag?
              p("if ((tag & 0x07) == 4) {");
              p("   return this;");
              p("}");
              
              p("switch (tag) {");
              // The end of stream..
              p("case 0:");
//              p("   this.setUnknownFields(unknownFields.build());");
              p("   return this;");
              p("default: {");
              
              //TODO: handle unknown field types.
//              p("   if (!parseUnknownField(input, unknownFields, extensionRegistry, tag)) {");
//              p("       this.setUnknownFields(unknownFields.build());");
//              p("       return this;");
//              p("   }");
              
              p("   break;");
              p("}");
              
              
              for (FieldDescriptor field : m.getFields().values()) {
                  String uname = uCamel(field.getName());
                  String setter = "set"+uname;
                  boolean repeated = field.getRule() == FieldDescriptor.REPEATED_RULE;
                  if( repeated ) {
                      setter = "get"+uname+"List().add";
                  }
                  
                  
                  
                  if( field.getType()==FieldDescriptor.STRING_TYPE ) {
                      p("case "+makeTag(field.getTag(), WIRETYPE_LENGTH_DELIMITED)+":");
                      indent();
                      p(setter+"(input.readString());");
                  } else if( field.getType()==FieldDescriptor.BYTES_TYPE ) {
                      p("case "+makeTag(field.getTag(), WIRETYPE_LENGTH_DELIMITED)+":");
                      indent();
                      p(setter+"(input.readBytes());");
                  } else if( field.getType()==FieldDescriptor.BOOL_TYPE ) {
                      p("case "+makeTag(field.getTag(), WIRETYPE_VARINT)+":");
                      indent();
                      p(setter+"(input.readBool());");
                  } else if( field.getType()==FieldDescriptor.DOUBLE_TYPE ) {
                      p("case "+makeTag(field.getTag(), WIRETYPE_FIXED64)+":");
                      indent();
                      p(setter+"(input.readDouble());");
                  } else if( field.getType()==FieldDescriptor.FLOAT_TYPE ) {
                      p("case "+makeTag(field.getTag(), WIRETYPE_FIXED32)+":");
                      indent();
                      p(setter+"(input.readFloat());");
                  } else if( field.getType()==FieldDescriptor.INT32_TYPE ) {
                      p("case "+makeTag(field.getTag(), WIRETYPE_VARINT)+":");
                      indent();
                      p(setter+"(input.readInt32());");
                  } else if( field.getType()==FieldDescriptor.INT64_TYPE ) {
                      p("case "+makeTag(field.getTag(), WIRETYPE_VARINT)+":");
                      indent();
                      p(setter+"(input.readInt64());");
                  } else if( field.getType()==FieldDescriptor.SINT32_TYPE ) {
                      p("case "+makeTag(field.getTag(), WIRETYPE_VARINT)+":");
                      indent();
                      p(setter+"(input.readSInt32());");
                  } else if( field.getType()==FieldDescriptor.SINT64_TYPE ) {
                      p("case "+makeTag(field.getTag(), WIRETYPE_VARINT)+":");
                      indent();
                      p(setter+"(input.readSInt64());");
                  } else if( field.getType()==FieldDescriptor.UINT32_TYPE ) {
                      p("case "+makeTag(field.getTag(), WIRETYPE_VARINT)+":");
                      indent();
                      p(setter+"(input.readUInt32());");
                  } else if( field.getType()==FieldDescriptor.UINT64_TYPE ) {
                      p("case "+makeTag(field.getTag(), WIRETYPE_VARINT)+":");
                      indent();
                      p(setter+"(input.readUInt64());");
                  } else if( field.getType()==FieldDescriptor.FIXED32_TYPE ) {
                      p("case "+makeTag(field.getTag(), WIRETYPE_FIXED32)+":");
                      indent();
                      p(setter+"(input.readFixed32());");
                  } else if( field.getType()==FieldDescriptor.FIXED64_TYPE ) {
                      p("case "+makeTag(field.getTag(), WIRETYPE_FIXED64)+":");
                      indent();
                      p(setter+"(input.readFixed64());");
                  } else if( field.getType()==FieldDescriptor.SFIXED32_TYPE ) {
                      p("case "+makeTag(field.getTag(), WIRETYPE_FIXED32)+":");
                      indent();
                      p(setter+"(input.readSFixed32());");
                  } else if( field.getType()==FieldDescriptor.SFIXED64_TYPE ) {
                      p("case "+makeTag(field.getTag(), WIRETYPE_FIXED64)+":");
                      indent();
                      p(setter+"(input.readSFixed64());");
                  } else if( field.getTypeDescriptor().isEnum() ) {
                      p("case "+makeTag(field.getTag(), WIRETYPE_VARINT)+":");
                      indent();
                      String type = javaType(field);
                      p("{");
                      indent();
                      p("int t = input.readEnum();");
                      p(""+type+" value = "+type+".valueOf(t);");
                      p("if( value !=null ) {");
                      indent();
                      p(setter+"(value);");
                      unindent();
                      p("}");
                      // TODO: else store it as an known
                      
                      unindent();
                      p("}");
                      
                  } else if ( field.getGroup()!=null ) {
                      p("case "+makeTag(field.getTag(), WIRETYPE_START_GROUP)+":");
                      indent();
                      String type = javaType(field);
                      if( repeated ) {
                          p(setter+"(readGroup(input, extensionRegistry, "+field.getTag()+", new "+type+"()));");
                      } else {
                          p("if (has"+uname+"()) {");
                          indent();
                          p("readGroup(input, extensionRegistry, "+field.getTag()+", get"+uname+"());");
                          unindent();
                          p("} else {");
                          indent();
                          p(setter+"(readGroup(input, extensionRegistry, "+field.getTag()+",new "+type+"()));");
                          unindent();
                          p("}");
                      }
                      p("");
                  } else {
                      p("case "+makeTag(field.getTag(), WIRETYPE_LENGTH_DELIMITED)+":");
                      indent();
                      String type = javaType(field);
                      if( repeated ) {
                          p(setter+"(readMessage(input, extensionRegistry, new "+type+"()));");
                      } else {
                          p("if (has"+uname+"()) {");
                          indent();
                          p("readMessage(input, extensionRegistry,get"+uname+"());");
                          unindent();
                          p("} else {");
                          indent();
                          p(setter+"(readMessage(input, extensionRegistry, new "+type+"()));");
                          unindent();
                          p("}");
                      }
                  }
                  p("break;");
                  unindent();
              }              
              p("}");
          } unindent();
          p("}"); 
        } unindent();
        p("}");
    }

    /**
     * @param m
     * @param className
     */
    private void generateMethodMergeFromBean(MessageDescriptor m, String className) {
        p("public "+className+" mergeFrom("+className+" other) {");
        indent();
        for (FieldDescriptor field : m.getFields().values()) {
            String uname = uCamel(field.getName());
            p("if (other.has"+uname+"()) {");
            indent();

            if( field.isScalarType() || field.getTypeDescriptor().isEnum() ) {
                if( field.isRepeated() ) {
                    p("get"+uname+"List().addAll(other.get"+uname+"List());");
                } else {
                    p("set"+uname+"(other.get"+uname+"());");
                }
            } else {
                
                String type = javaType(field);
                // It's complex type...
                if( field.isRepeated() ) {
                    p("for("+type+" element: other.get"+uname+"List() ) {");
                    indent();
                        p("get"+uname+"List().add(element.clone());");
                    unindent();
                    p("}");
                } else {
                    p("if (has"+uname+"()) {");
                    indent();
                    p("get"+uname+"().mergeFrom(other.get"+uname+"());");
                    unindent();
                    p("} else {");
                    indent();
                    p("set"+uname+"(other.get"+uname+"().clone());");
                    unindent();
                    p("}");
                }
            }
            unindent();
            p("}");
        }
        p("return this;");
        unindent();
        p("}");
        p();
    }

    /**
     * @param m
     */
    private void generateMethodClear(MessageDescriptor m) {
        p("public final void clear() {");
        indent();
        p("memoizedSerializedSize=-1;");
        for (FieldDescriptor field : m.getFields().values()) {
            String uname = uCamel(field.getName());
            p("clear" + uname + "();");
        }
        unindent();
        p("}");
        p();
    }

    private void generateMethodAssertInitialized(MessageDescriptor m, String className) {
        
        
        
        p("public final boolean isInitialized() {");
        indent();
        p("return missingFields().isEmpty();");
        unindent();
        p("}");
        p();
        
        p("public final "+className+" assertInitialized() throws org.apache.activemq.protobuf.UninitializedMessageException {");
        indent();
        p("java.util.ArrayList<String> missingFields = missingFields();");
        p("if( !missingFields.isEmpty()) {");
        indent();
        p("throw new org.apache.activemq.protobuf.UninitializedMessageException(missingFields);");
        unindent();
        p("}");
        p("return this;");
        unindent();
        p("}");
        p();
        
        p("private final "+className+" checktInitialized() throws com.google.protobuf.InvalidProtocolBufferException {");
        indent();
        p("java.util.ArrayList<String> missingFields = missingFields();");
        p("if( !missingFields.isEmpty()) {");
        indent();
        p("throw new org.apache.activemq.protobuf.UninitializedMessageException(missingFields).asInvalidProtocolBufferException();");
        unindent();
        p("}");
        p("return this;");
        unindent();
        p("}");
        p();

        p("public final java.util.ArrayList<String> missingFields() {");
        indent();
        p("java.util.ArrayList<String> missingFields = new java.util.ArrayList<String>();");
        
        for (FieldDescriptor field : m.getFields().values()) {
            String uname = uCamel(field.getName());
            if( field.isRequired() ) {
                p("if(  !has" + uname + "() ) {");
                indent();
                p("missingFields.add(\""+field.getName()+"\");");
                unindent();
                p("}");
            }
        }
        
        for (FieldDescriptor field : m.getFields().values()) {
            if( field.getTypeDescriptor()!=null && !field.getTypeDescriptor().isEnum()) {
                String uname = uCamel(field.getName());
                p("if( has" + uname + "() ) {");
                indent();
                if( !field.isRepeated() ) {
                    p("try {");
                    indent();
                    p("get" + uname + "().assertInitialized();");
                    unindent();
                    p("} catch (org.apache.activemq.protobuf.UninitializedMessageException e){");
                    indent();
                    p("missingFields.addAll(prefix(e.getMissingFields(),\""+field.getName()+".\"));");
                    unindent();
                    p("}");
                } else {
                    String type = javaCollectionType(field);
                    p("java.util.List<"+type+"> l = get" + uname + "List();");
                    p("for( int i=0; i < l.size(); i++ ) {");
                    indent();
                    p("try {");
                    indent();
                    p("l.get(i).assertInitialized();");
                    unindent();
                    p("} catch (org.apache.activemq.protobuf.UninitializedMessageException e){");
                    indent();
                    p("missingFields.addAll(prefix(e.getMissingFields(),\""+field.getName()+"[\"+i+\"]\"));");
                    unindent();
                    p("}");
                    unindent();
                    p("}");
                }
                unindent();
                p("}");
            }
        }
        p("return missingFields;");
        unindent();
        p("}");
        p();
    }

    private void generateMethodToString(MessageDescriptor m) {
        
        p("public String toString() {");
        indent();
        p("return toString(new java.lang.StringBuilder(), \"\").toString();");
        unindent();
        p("}");
        p();

        p("public java.lang.StringBuilder toString(java.lang.StringBuilder sb, String prefix) {");
        indent();
        
        for (FieldDescriptor field : m.getFields().values()) {
            String uname = uCamel(field.getName());
            p("if(  has" + uname + "() ) {");
            indent();
            if( field.isRepeated() ) {
                String type = javaCollectionType(field);
                p("java.util.List<"+type+"> l = get" + uname + "List();");
                p("for( int i=0; i < l.size(); i++ ) {");
                indent();
                if( field.getTypeDescriptor()!=null && !field.getTypeDescriptor().isEnum()) {
                    p("sb.append(prefix+\""+field.getName()+"[\"+i+\"] {\\n\");");
                    p("l.get(i).toString(sb, prefix+\"  \");");
                    p("sb.append(\"}\\n\");");
                } else {
                    p("sb.append(prefix+\""+field.getName()+"[\"+i+\"]: \");");
                    p("sb.append(l.get(i));");
                    p("sb.append(\"\\n\");");
                }
                unindent();
                p("}");
            } else {
                if( field.getTypeDescriptor()!=null && !field.getTypeDescriptor().isEnum()) {
                    p("sb.append(prefix+\""+field.getName()+" {\\n\");");
                    p("get" + uname + "().toString(sb, prefix+\"  \");");
                    p("sb.append(\"}\\n\");");
                } else {
                    p("sb.append(prefix+\""+field.getName()+": \");");
                    p("sb.append(get" + uname + "());");
                    p("sb.append(\"\\n\");");
                }
            }
            unindent();
            p("}");
        }

        
        p("return sb;");
        unindent();
        p("}");
        p();

    }

    /**
     * @param field
     * @param className 
     */
    private void generateFieldAccessor(String className, FieldDescriptor field) {
        
        String lname = lCamel(field.getName());
        String uname = uCamel(field.getName());
        String type = field.getRule()==FieldDescriptor.REPEATED_RULE ? javaCollectionType(field):javaType(field);
        String typeDefault = javaTypeDefault(field);
        boolean primitive = field.getTypeDescriptor()==null || field.getTypeDescriptor().isEnum();
        boolean repeated = field.getRule()==FieldDescriptor.REPEATED_RULE;

        // Create the fields..
        p("// " + field.getRule() + " " + field.getType() + " " + field.getName() + " = " + field.getTag() + ";");
        
        if( repeated ) {
            p("private java.util.List<" + type + "> f_" + lname + ";");
            p();
            
            // Create the field accessors
            p("public boolean has" + uname + "() {");
            indent();
            p("return this.f_" + lname + "!=null && !this.f_" + lname + ".isEmpty();");
            unindent();
            p("}");
            p();

            p("public java.util.List<" + type + "> get" + uname + "List() {");
            indent();
            p("if( this.f_" + lname + " == null ) {");
            indent();
            p("this.f_" + lname + " = new java.util.ArrayList<" + type + ">();");
            unindent();
            p("}");
            p("return this.f_" + lname + ";");
            unindent();
            p("}");
            p();

            p("public "+className+" set" + uname + "List(java.util.List<" + type + "> " + lname + ") {");
            indent();
            p("this.f_" + lname + " = " + lname + ";");
            p("return this;");
            unindent();
            p("}");
            p();
            
            p("public int get" + uname + "Count() {");
            indent();
            p("if( this.f_" + lname + " == null ) {");
            indent();
            p("return 0;");
            unindent();
            p("}");
            p("return this.f_" + lname + ".size();");
            unindent();
            p("}");
            p();
            
            p("public " + type + " get" + uname + "(int index) {");
            indent();
            p("if( this.f_" + lname + " == null ) {");
            indent();
            p("return null;");
            unindent();
            p("}");
            p("return this.f_" + lname + ".get(index);");
            unindent();
            p("}");
            p();
                            
            p("public "+className+" set" + uname + "(int index, " + type + " value) {");
            indent();
            p("get" + uname + "List().set(index, value);");
            p("return this;");
            unindent();
            p("}");
            p();
            
            p("public "+className+" add" + uname + "(" + type + " value) {");
            indent();
            p("get" + uname + "List().add(value);");
            p("return this;");
            unindent();
            p("}");
            p();
            
            p("public "+className+" addAll" + uname + "(java.lang.Iterable<? extends " + type + "> collection) {");
            indent();
            p("super.addAll(collection, get" + uname + "List());");
            p("return this;");
            unindent();
            p("}");
            p();

            p("public void clear" + uname + "() {");
            indent();
            p("this.f_" + lname + " = null;");
            unindent();
            p("}");
            p();

        } else {
            
            p("private " + type + " f_" + lname + " = "+typeDefault+";");
            if (primitive) {
                p("private boolean b_" + lname + ";");
            }
            p();
            
            // Create the field accessors
            p("public boolean has" + uname + "() {");
            indent();
            if (primitive) {
                p("return this.b_" + lname + ";");
            } else {
                p("return this.f_" + lname + "!=null;");
            }
            unindent();
            p("}");
            p();

            p("public " + type + " get" + uname + "() {");
            indent();
            if( field.getTypeDescriptor()!=null && !field.getTypeDescriptor().isEnum()) {
                p("if( this.f_" + lname + " == null ) {");
                indent();
                p("this.f_" + lname + " = new " + type + "();");
                unindent();
                p("}");
            }
            p("return this.f_" + lname + ";");
            unindent();
            p("}");
            p();

            p("public "+className+" set" + uname + "(" + type + " " + lname + ") {");
            indent();
            if (primitive) {
                p("this.b_" + lname + " = true;");
            }
            p("this.f_" + lname + " = " + lname + ";");
            p("return this;");
            unindent();
            p("}");
            p();

            p("public void clear" + uname + "() {");
            indent();
            if (primitive) {
                p("this.b_" + lname + " = false;");
            }
            p("this.f_" + lname + " = " + typeDefault + ";");
            unindent();
            p("}");
            p();
        }

    }

    private String javaTypeDefault(FieldDescriptor field) {
        OptionDescriptor defaultOption = field.getOptions().get("default");
        if( defaultOption!=null ) {
            if( field.isStringType() ) {
                return asJavaString(defaultOption.getValue());
            } else if( field.getType() == FieldDescriptor.BYTES_TYPE ) {
                return "com.google.protobuf.ByteString.copyFromUtf8("+asJavaString(defaultOption.getValue())+")";
            } else if( field.isInteger32Type() ) {
                int v;
                if( field.getType() == FieldDescriptor.UINT32_TYPE ) {
                    v = TextFormat.parseUInt32(defaultOption.getValue());
                } else {
                    v = TextFormat.parseInt32(defaultOption.getValue());
                }
                return ""+v;
            } else if( field.isInteger64Type() ) {
                long v;
                if( field.getType() == FieldDescriptor.UINT64_TYPE ) {
                    v = TextFormat.parseUInt64(defaultOption.getValue());
                } else {
                    v = TextFormat.parseInt64(defaultOption.getValue());
                }
                return ""+v+"l";
            } else if( field.getType() == FieldDescriptor.DOUBLE_TYPE ) {
                double v = Double.valueOf(defaultOption.getValue());
                return ""+v+"d";
            } else if( field.getType() == FieldDescriptor.FLOAT_TYPE ) {
                float v = Float.valueOf(defaultOption.getValue());
                return ""+v+"f";
            } else if( field.getType() == FieldDescriptor.BOOL_TYPE ) {
                boolean v = Boolean.valueOf(defaultOption.getValue());
                return ""+v;
            } else if( field.getTypeDescriptor()!=null && field.getTypeDescriptor().isEnum() ) {
                return javaType(field)+"."+defaultOption.getValue();
            }
            return defaultOption.getValue();
        } else {
            if( field.isNumberType() ) {
                return "0";
            }
            if( field.getType() == FieldDescriptor.BOOL_TYPE ) {
                return "false";
            }
            return "null";
        }
    }
        
    static final char HEX_TABLE[] = new char[]{ '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};
    
    private String asJavaString(String value) {
        StringBuilder sb = new StringBuilder(value.length()+2);
        sb.append("\"");
        for (int i = 0; i < value.length(); i++) {
            
          char b = value.charAt(i);
          switch (b) {
            // Java does not recognize \a or \v, apparently.
            case '\b': sb.append("\\b" ); break;
            case '\f': sb.append("\\f" ); break;
            case '\n': sb.append("\\n" ); break;
            case '\r': sb.append("\\r" ); break;
            case '\t': sb.append("\\t" ); break;
            case '\\': sb.append("\\\\"); break;
            case '\'': sb.append("\\\'"); break;
            case '"' : sb.append("\\\""); break;
            default:
              if (b >= 0x20 && b <'Z') {
                sb.append((char) b);
              } else {
                sb.append("\\u");
                sb.append(HEX_TABLE[(b >>> 12) & 0x0F] );
                sb.append(HEX_TABLE[(b >>> 8) & 0x0F] );
                sb.append(HEX_TABLE[(b >>> 4) & 0x0F] );
                sb.append(HEX_TABLE[b & 0x0F] );
              }
              break;
          }
          
        }
        sb.append("\"");
        return sb.toString();
    }

    private void generateEnum(EnumDescriptor ed) {
        String uname = uCamel(ed.getName());

        String staticOption = "static ";
        if( multipleFiles && ed.getParent()==null ) {
            staticOption="";
        }

        // TODO Auto-generated method stub
        p();
        p("public "+staticOption+"enum " +uname + " {");
        indent();
        
        
        p();
        int counter=0;
        for (EnumFieldDescriptor field : ed.getFields().values()) {
            boolean last = counter+1 == ed.getFields().size();
            p(field.getName()+"(\""+field.getName()+"\", "+field.getValue()+")"+(last?";":",")); 
            counter++;
        }
        p();
        p("private final String name;");
        p("private final int value;");
        p();
        p("private "+uname+"(String name, int value) {");
        p("   this.name = name;");
        p("   this.value = value;");
        p("}");
        p();
        p("public final int getNumber() {");
        p("   return value;");
        p("}");
        p();
        p("public final String toString() {");
        p("   return name;");
        p("}");
        p();
        p("public static "+uname+" valueOf(int value) {");
        p("   switch (value) {");
        
        // It's possible to define multiple ENUM fields with the same value.. 
        //   we only want to put the first one into the switch statement.
        HashSet<Integer> values = new HashSet<Integer>();
        for (EnumFieldDescriptor field : ed.getFields().values()) {
            if( !values.contains(field.getValue()) ) {
                p("   case "+field.getValue()+":");
                p("      return "+field.getName()+";");
                values.add(field.getValue());
            }
            
        }
        p("   default:");
        p("      return null;");
        p("   }");
        p("}");
        
        
        unindent();
        p("}");
        p();
    }

    
    private String javaCollectionType(FieldDescriptor field) {
        if( field.isInteger32Type() ) {
            return "java.lang.Integer";
        }
        if( field.isInteger64Type() ) {
            return "java.lang.Long";
        }
        if( field.getType() == FieldDescriptor.DOUBLE_TYPE ) {
            return "java.lang.Double";
        }
        if( field.getType() == FieldDescriptor.FLOAT_TYPE ) {
            return "java.lang.Float";
        }
        if( field.getType() == FieldDescriptor.STRING_TYPE ) {
            return "java.lang.String";
        }
        if( field.getType() == FieldDescriptor.BYTES_TYPE ) {
            return "com.google.protobuf.ByteString";
        }
        if( field.getType() == FieldDescriptor.BOOL_TYPE ) {
            return "java.lang.Boolean";
        }
        
        TypeDescriptor descriptor = field.getTypeDescriptor();
        return javaType(descriptor);
    }

    private String javaType(FieldDescriptor field) {
        if( field.isInteger32Type() ) {
            return "int";
        }
        if( field.isInteger64Type() ) {
            return "long";
        }
        if( field.getType() == FieldDescriptor.DOUBLE_TYPE ) {
            return "double";
        }
        if( field.getType() == FieldDescriptor.FLOAT_TYPE ) {
            return "float";
        }
        if( field.getType() == FieldDescriptor.STRING_TYPE ) {
            return "java.lang.String";
        }
        if( field.getType() == FieldDescriptor.BYTES_TYPE ) {
            return "com.google.protobuf.ByteString";
        }
        if( field.getType() == FieldDescriptor.BOOL_TYPE ) {
            return "boolean";
        }
        
        TypeDescriptor descriptor = field.getTypeDescriptor();
        return javaType(descriptor);
    }

    private String javaType(TypeDescriptor descriptor) {
        ProtoDescriptor p = descriptor.getProtoDescriptor();
        if( p != proto ) {
            // Try to keep it short..
            String othePackage = javaPackage(p);
            if( equals(othePackage,javaPackage(proto) ) ) {
                return javaClassName(p)+"."+descriptor.getQName();
            }
            // Use the fully qualified class name.
            return othePackage+"."+javaClassName(p)+"."+descriptor.getQName();
        }
        return descriptor.getQName();
    }
    
    private boolean equals(String o1, String o2) {
        if( o1==o2 )
            return true;
        if( o1==null || o2==null )
            return false;
        return o1.equals(o2);
    }

    private String javaClassName(ProtoDescriptor proto) {
        return getOption(proto, "java_outer_classname", uCamel(removeFileExtension(proto.getName())));
    }
    
    private boolean isMultipleFilesEnabled(ProtoDescriptor proto) {
        return "true".equals(getOption(proto, "java_multiple_files", "false"));
    }


    private String javaPackage(ProtoDescriptor proto) {
        String name = proto.getPackageName();
        if( name!=null ) {
            name = name.replace('-', '.');
            name = name.replace('/', '.');
        }
        return getOption(proto, "java_package", name);
    }


    // ----------------------------------------------------------------
    // Internal Helper methods
    // ----------------------------------------------------------------

    private void indent() {
        indent++;
    }

    private void unindent() {
        indent--;
    }

    private void p(String line) {
        // Indent...
        for (int i = 0; i < indent; i++) {
            w.print("   ");
        }
        // Then print.
        w.println(line);
    }

    private void p() {
        w.println();
    }

    private String getOption(ProtoDescriptor proto, String optionName, String defaultValue) {
        OptionDescriptor optionDescriptor = proto.getOptions().get(optionName);
        if (optionDescriptor == null) {
            return defaultValue;
        }
        return optionDescriptor.getValue();
    }

    static private String removeFileExtension(String name) {
        return name.replaceAll("\\..*", "");
    }

    static private String uCamel(String name) {
        boolean upNext=true;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if( Character.isJavaIdentifierPart(c) && Character.isLetterOrDigit(c)) {
                if( upNext ) {
                    c = Character.toUpperCase(c);
                    upNext=false;
                }
                sb.append(c);
            } else {
                upNext=true;
            }
        }
        return sb.toString();
    }

    static private String lCamel(String name) {
        if( name == null || name.length()<1 )
            return name;
        String uCamel = uCamel(name);
        return uCamel.substring(0,1).toLowerCase()+uCamel.substring(1);
    }

    public File getOut() {
        return out;
    }

    public void setOut(File outputDirectory) {
        this.out = outputDirectory;
    }

    public File[] getPath() {
        return path;
    }

    public void setPath(File[] path) {
        this.path = path;
    }
    
}