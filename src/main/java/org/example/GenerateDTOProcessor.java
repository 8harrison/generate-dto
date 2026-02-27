package org.example;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.*;

import static java.lang.Package.getPackage;

@SupportedAnnotationTypes("org.example.GenerateDTO")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class GenerateDTOProcessor extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations,
                           RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(GenerateDTO.class)) {
            TypeElement classElement = (TypeElement) element;
            GenerateDTO annotation = classElement.getAnnotation(GenerateDTO.class);
            String[] selectedFields = annotation.fields();
            String[] flattenRelations = annotation.flattenRelations();
            NestedDTO[] nestedDTOs = annotation.nestedDTOs();

            // Cria um mapa para lookup rápido
            // Mapa para lookup de DTOs aninhados
            Map<String, String> nestedDTOMap = new HashMap<>();

            String packageName = processingEnv.getElementUtils()
                    .getPackageOf(classElement)
                    .getQualifiedName()
                    .toString();

            String className = classElement.getSimpleName().toString();

            for (NestedDTO nested : nestedDTOs) {
                nestedDTOMap.put(nested.field(), nested.dto());
                String nestedDtoName = nested.dto();
                String nestedClassName = nestedDtoName.replace("DTO", "");

                // Procura a classe nested no projeto
                TypeElement nestedElement = processingEnv.getElementUtils()
                        .getTypeElement(packageName + "." + nestedClassName);

                if (nestedElement != null) {
                    GenerateDTO nestedAnnotation = nestedElement.getAnnotation(GenerateDTO.class);

                    if (nestedAnnotation != null) {
                        for (NestedDTO backRef : nestedAnnotation.nestedDTOs()) {
                            String backDto = backRef.dto();

                            if (backDto.equals(className + "DTO")) {
                                error(
                                        "Ciclo direto detectado entre "
                                                + className + " e " + nestedClassName
                                                + ". Use flattenRelations em um dos lados.",
                                        classElement
                                );
                                return true;
                            }
                        }
                    }
                }
            }

            String dtoName = className + "DTO";
            try {
                JavaFileObject file = processingEnv.getFiler()
                        .createSourceFile(packageName + "." + dtoName);
                try (Writer w = file.openWriter()) {

                    Set<String> imports = collectImports(
                            classElement,
                            selectedFields,
                            nestedDTOMap,
                            packageName
                    );

                    w.write("package " + packageName + ";\n\n");

                    writeImports(w, imports);

                    w.write("\n");

                    w.write("public class " + dtoName + " {\n\n");

                    // Gera os campos
                    // ========== CAMPOS ==========
                    for (Element enclosedElement : classElement.getEnclosedElements()) {
                        if (enclosedElement.getKind() == ElementKind.FIELD) {
                            VariableElement field = (VariableElement) enclosedElement;
                            String fieldName = field.getSimpleName().toString();

                            if (selectedFields.length == 0 || contains(selectedFields, fieldName)) {
                                TypeMirror fieldType = field.asType();
                                String fieldTypeString = fieldType.toString();
                                if (contains(flattenRelations, fieldName)) {
                                    w.write("    private Long " + fieldName + "Id;\n");
                                } else  {
                                    String dtoType = resolveNestedDTO(fieldName, fieldType, nestedDTOMap);

                                    if (dtoType != null) {
                                        if (isMap(fieldType)) {
                                            gerarCampoMap(w, fieldName, fieldType, dtoType);
                                        }
                                        else if (isCollection(fieldType)) {
                                            String collectionType = getCollectionType(fieldType);
                                            w.write("    private " + collectionType + "<" + dtoType + "> " + fieldName + ";\n");
                                        }
                                        else {
                                            w.write("    private " + dtoType + " " + fieldName + ";\n");
                                        }
                                    } else {
                                        w.write("    private " + fieldTypeString + " " + fieldName + ";\n");
                                    }
                                }
                            }
                        }
                    }

                    w.write("\n");

                    gerarGetterESetters(w, classElement, selectedFields, flattenRelations, nestedDTOMap);

                    gerarMetodoFrom(w, className, classElement, selectedFields, flattenRelations, nestedDTOMap, dtoName);

                    gerarMetodoToModel(w, className, classElement, selectedFields, flattenRelations, nestedDTOMap);

                    gerarMetodoToString(w, classElement, selectedFields, flattenRelations, dtoName, nestedDTOMap);

                    gerarEqualsHashCode(w, classElement, selectedFields, flattenRelations, dtoName);

                    w.write("}\n");
                }
                System.out.println("✅ GERADO: " + packageName + "." + dtoName);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return true;
    }

    private void gerarGetterESetters(Writer w,
                                     TypeElement classElement,
                                     String[] selectedFields,
                                     String[] flattenRelations,
                                     Map<String, String> nestedDTOMap) throws IOException {
        // Gera os getters e setters
        // ========== GETTERS E SETTERS ==========
        for (Element enclosedElement : classElement.getEnclosedElements()) {
            if (enclosedElement.getKind() == ElementKind.FIELD) {
                VariableElement field = (VariableElement) enclosedElement;
                String fieldName = field.getSimpleName().toString();
                if (selectedFields.length == 0 || contains(selectedFields, fieldName)) {
                    TypeMirror fieldType = field.asType();
                    String fieldTypeString = fieldType.toString();
                    String actualFieldName = fieldName;
                    String actualFieldType = fieldTypeString;
                    if (contains(flattenRelations, fieldName)) {
                        actualFieldName = fieldName + "Id";
                        actualFieldType = "Long";
                    } else {
                        String dtoType = resolveNestedDTO(fieldName, fieldType, nestedDTOMap);

                        if (contains(flattenRelations, fieldName)) {
                            actualFieldName = fieldName + "Id";
                            actualFieldType = "Long";
                        }
                        else if (dtoType != null) {
                            if (isMap(fieldType)) {
                                String[] generics = getMapGenericTypes(fieldType);
                                String keyType = getSimpleTypeName(generics[0]);
                                actualFieldType = "java.util.Map<" + keyType + ", " + dtoType + ">";
                            }
                            else if (isCollection(fieldType)) {
                                String collectionType = getCollectionType(fieldType);
                                actualFieldType = collectionType + "<" + dtoType + ">";
                            }
                            else {
                                actualFieldType = dtoType;
                            }
                        }
                    }
                    String capitalizedFieldName = actualFieldName.substring(0, 1).toUpperCase()
                            + actualFieldName.substring(1);
                    // Getter
                    w.write("    public " + actualFieldType + " get" + capitalizedFieldName + "() {\n");
                    w.write("        return " + actualFieldName + ";\n");
                    w.write("    }\n\n");
                    // Setter
                    w.write("    public void set" + capitalizedFieldName + "(" + actualFieldType + " " + actualFieldName + ") {\n");
                    w.write("        this." + actualFieldName + " = " + actualFieldName + ";\n");
                    w.write("    }\n\n");
                }
            }
        }
    }

    private void gerarMetodoFrom(Writer w,
                                 String className,
                                 TypeElement classElement,
                                 String[] selectedFields,
                                 String[] flattenRelations,
                                 Map<String, String> nestedDTOMap,
                                 String dtoName
    ) throws IOException {
        // ========== MÉTODO FROM ==========
        w.write("    public static " + dtoName + " from(" + className + " model) {\n");
        w.write("        if (model == null) {\n");
        w.write("            return null;\n");
        w.write("        }\n\n");
        w.write("        " + dtoName + " dto = new " + dtoName + "();\n\n");
        for (Element enclosedElement : classElement.getEnclosedElements()) {
            if (enclosedElement.getKind() == ElementKind.FIELD) {
                VariableElement field = (VariableElement) enclosedElement;
                String fieldName = field.getSimpleName().toString();
                if (selectedFields.length == 0 || contains(selectedFields, fieldName)) {
                    TypeMirror fieldType = field.asType();
                    String capitalizedFieldName = fieldName.substring(0, 1).toUpperCase()
                            + fieldName.substring(1);
                    if (contains(flattenRelations, fieldName)) {
                        // Relacionamento flatten → extrai o ID
                        w.write("        if (model.get" + capitalizedFieldName + "() != null) {\n");
                        w.write("            dto.set" + capitalizedFieldName + "Id(model.get" + capitalizedFieldName + "().getId());\n");
                        w.write("        }\n");
                    } else {
                        String nestedDTOType = resolveNestedDTO(fieldName, fieldType, nestedDTOMap);

                        if (nestedDTOType != null) {
                            if (isMap(fieldType)) {
                                gerarFromMap(w, fieldName, capitalizedFieldName, nestedDTOType);
                            }
                            else if (isCollection(fieldType)) {
                                // código existente
                                // Coleção → converte cada elemento
                                w.write("        if (model.get" + capitalizedFieldName + "() != null) {\n");
                                w.write("            dto.set" + capitalizedFieldName + "(model.get" + capitalizedFieldName + "().stream()\n");
                                w.write("                .map(" + nestedDTOType + "::from)\n");
                                w.write("                .collect(java.util.stream.Collectors.toList()));\n");
                                w.write("        }\n");
                            }
                            else {
                                w.write("        if (model.get" + capitalizedFieldName + "() != null) {\n");
                                w.write("            dto.set" + capitalizedFieldName + "(" + nestedDTOType + ".from(model.get" + capitalizedFieldName + "()));\n");
                                w.write("        }\n");
                            }
                        }
                        else {
                            // Campo simples → copia direto
                            w.write("        dto.set" + capitalizedFieldName + "(model.get" + capitalizedFieldName + "());\n");
                        }
                    }
                }
            }
        }
        w.write("\n        return dto;\n");
        w.write("    }\n");
    }

    private void gerarMetodoToModel(
            Writer w,
            String className,
            TypeElement classElement,
            String[] selectedFields,
            String[] flattenRelations,
            Map<String, String> nestedDTOMap
    ) throws IOException {
        // ========== MÉTODO TOMODEL ==========
        w.write("    public " + className + " toModel() {\n");
        w.write("        " + className + " model = new " + className + "();\n\n");
        for (Element enclosedElement : classElement.getEnclosedElements()) {
            if (enclosedElement.getKind() == ElementKind.FIELD) {
                VariableElement field = (VariableElement) enclosedElement;
                String fieldName = field.getSimpleName().toString();
                if (selectedFields.length == 0 || contains(selectedFields, fieldName)) {
                    TypeMirror fieldType = field.asType();
                    String capitalizedFieldName = fieldName.substring(0, 1).toUpperCase()
                            + fieldName.substring(1);
                    if (contains(flattenRelations, fieldName)) {
                        // Relacionamento flatten → ignora (só temos o ID)
                        w.write("        // " + fieldName + " (flatten) não pode ser reconstruído apenas com ID\n");
                    } else {

                        String nestedDTOType = resolveNestedDTO(fieldName, fieldType, nestedDTOMap);

                        if (nestedDTOType != null) {
                            if (isMap(fieldType)) {
                                gerarToModelMap(w, fieldName, capitalizedFieldName);
                            }
                            else if (isCollection(fieldType)) {
                                // existente
                                // Coleção → converte cada elemento
                                w.write("        if (this." + fieldName + " != null) {\n");
                                w.write("            model.set" + capitalizedFieldName + "(\n");
                                w.write("                this." + fieldName + ".stream()\n");
                                w.write("                    .map(e -> e.toModel())\n");
                                w.write("                    .collect(java.util.stream.Collectors.toList())\n");
                                w.write("            );\n");
                                w.write("        }\n");
                            }
                            else {
                                w.write("        if (this." + fieldName + " != null) {\n");
                                w.write("            model.set" + capitalizedFieldName + "(this." + fieldName + ".toModel());\n");
                                w.write("        }\n");
                            }
                        } else {
                            // Campo simples → copia direto
                            w.write("        model.set" + capitalizedFieldName + "(this." + fieldName + ");\n");
                        }
                    }
                }
            }
        }
        w.write("\n        return model;\n");
        w.write("    }\n\n");
    }

    private void gerarMetodoToString(Writer w,
                                     TypeElement classElement,
                                     String[] selectedFields,
                                     String[] flattenRelations,
                                     String dtoName,
                                     Map<String, String> nestedDTOMap
    ) throws IOException {
        // ========== MÉTODO TOSTRING ==========
        w.write("    @Override\n");
        w.write("    public String toString() {\n");
        w.write("        return \"" + dtoName + "{\" +\n");

        boolean first = true;
        for (Element enclosedElement : classElement.getEnclosedElements()) {
            if (enclosedElement.getKind() == ElementKind.FIELD) {
                VariableElement field = (VariableElement) enclosedElement;
                String fieldName = field.getSimpleName().toString();
                if (selectedFields.length == 0 || contains(selectedFields, fieldName)) {
                    String actualFieldName = fieldName;

                    // Ajusta nome para flatten
                    if (contains(flattenRelations, fieldName)) {
                        actualFieldName = fieldName + "Id";
                    }
                    if (!first) {
                        w.write("               \", ");
                    } else {
                        w.write("               \"");
                        first = false;
                    }
                    String fieldType = field.asType().toString();
                    boolean isString = field.asType().toString().equals("java.lang.String");
                    boolean isFlatten = contains(flattenRelations, fieldName);
                    boolean isNested = nestedDTOMap.containsKey(fieldName);
                    boolean isCollection = isCollection(field.asType());

                    // String precisa de aspas simples
                    if (isString) {
                        w.write(actualFieldName + "='\" + " + actualFieldName + " + '\\'' +\n");
                    } else {
                        w.write(actualFieldName + "=\" + " + actualFieldName + " +\n");
                    }
                }
            }
        }

        w.write("               \"}\";\n");
        w.write("    }\n");
    }

    private void gerarEqualsHashCode(Writer w,
                                     TypeElement classElement,
                                     String[] selectedFields,
                                     String[] flattenRelations,
                                     String dtoName) throws IOException {

        // equals
        w.write("    @Override\n");
        w.write("    public boolean equals(Object o) {\n");
        w.write("        if (this == o) return true;\n");
        w.write("        if (o == null || getClass() != o.getClass()) return false;\n");
        w.write("        " + dtoName + " that = (" + dtoName + ") o;\n");
        w.write("        return ");

        boolean first = true;

        for (Element enclosedElement : classElement.getEnclosedElements()) {
            if (enclosedElement.getKind() == ElementKind.FIELD) {
                String fieldName = enclosedElement.getSimpleName().toString();

                if (selectedFields.length == 0 || contains(selectedFields, fieldName)) {
                    String actualField = contains(flattenRelations, fieldName)
                            ? fieldName + "Id"
                            : fieldName;

                    if (!first) {
                        w.write(" && ");
                    }

                    w.write("java.util.Objects.equals(" + actualField + ", that." + actualField + ")");
                    first = false;
                }
            }
        }

        w.write(";\n");
        w.write("    }\n\n");

        // hashCode
        w.write("    @Override\n");
        w.write("    public int hashCode() {\n");
        w.write("        return java.util.Objects.hash(");

        first = true;
        for (Element enclosedElement : classElement.getEnclosedElements()) {
            if (enclosedElement.getKind() == ElementKind.FIELD) {
                String fieldName = enclosedElement.getSimpleName().toString();

                if (selectedFields.length == 0 || contains(selectedFields, fieldName)) {
                    String actualField = contains(flattenRelations, fieldName)
                            ? fieldName + "Id"
                            : fieldName;

                    if (!first) {
                        w.write(", ");
                    }

                    w.write(actualField);
                    first = false;
                }
            }
        }

        w.write(");\n");
        w.write("    }\n\n");
    }

    private boolean contains(String[] array, String value) {
        for (String s : array) {
            if (s.equals(value)) {
                return true;
            }
        }
        return false;
    }

    // Verifica se o tipo é uma Collection (List, Set, etc)
    private boolean isCollection(TypeMirror type) {
        String typeName = type.toString();
        return typeName.startsWith("java.util.List<") ||
                typeName.startsWith("java.util.Set<") ||
                typeName.startsWith("java.util.Collection<");
    }

    // Extrai o tipo genérico (ex: List<Endereco> → Endereco)
    private String getGenericType(TypeMirror type) {
        if (type instanceof DeclaredType) {
            DeclaredType declaredType = (DeclaredType) type;
            List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();

            if (!typeArguments.isEmpty()) {
                return typeArguments.get(0).toString();
            }
        }
        return null;
    }

    // Extrai o nome simples do tipo (org.example.Endereco → Endereco)
    private String getSimpleTypeName(String fullTypeName) {
        if (fullTypeName == null) return null;
        int lastDot = fullTypeName.lastIndexOf('.');
        return lastDot >= 0 ? fullTypeName.substring(lastDot + 1) : fullTypeName;
    }

    // Extrai o tipo da coleção (List, Set, Collection)
    private String getCollectionType(TypeMirror type) {
        String typeName = type.toString();
        if (typeName.startsWith("java.util.List<")) return "List";
        if (typeName.startsWith("java.util.Set<")) return "Set";
        if (typeName.startsWith("java.util.Collection<")) return "Collection";
        return "List"; // default
    }

    private void error(String message, Element element) {
        processingEnv.getMessager().printMessage(
                javax.tools.Diagnostic.Kind.ERROR,
                message,
                element
        );
    }

    private boolean isMap(TypeMirror type) {
        return type.toString().startsWith("java.util.Map<");
    }

    private boolean isList(TypeMirror type) {
        return type.toString().startsWith("java.util.List<");
    }

    private boolean isSet(TypeMirror type) {
        return type.toString().startsWith("java.util.Set<");
    }

    private String[] getMapGenericTypes(TypeMirror type) {
        if (type instanceof DeclaredType) {
            DeclaredType declaredType = (DeclaredType) type;
            List<? extends TypeMirror> args = declaredType.getTypeArguments();

            if (args.size() == 2) {
                return new String[]{
                        args.get(0).toString(),
                        args.get(1).toString()
                };
            }
        }
        return null;
    }

    private void gerarCampoMap(Writer w,
                               String fieldName,
                               TypeMirror fieldType,
                               String dtoType) throws IOException {

        String[] generics = getMapGenericTypes(fieldType);

        String keyType = getSimpleTypeName(generics[0]);

        w.write("    private java.util.Map<"
                + keyType + ", "
                + dtoType + "> "
                + fieldName + ";\n");
    }

    private void gerarFromMap(Writer w,
                              String fieldName,
                              String capitalizedFieldName,
                              String dtoType) throws IOException {

        w.write("        if (model.get" + capitalizedFieldName + "() != null) {\n");
        w.write("            dto.set" + capitalizedFieldName + "(\n");
        w.write("                model.get" + capitalizedFieldName + "().entrySet().stream()\n");
        w.write("                    .collect(java.util.stream.Collectors.toMap(\n");
        w.write("                        e -> e.getKey(),\n");
        w.write("                        e -> " + dtoType + ".from(e.getValue())\n");
        w.write("                    )));\n");
        w.write("        }\n");
    }

    private void gerarToModelMap(Writer w,
                                 String fieldName,
                                 String capitalizedFieldName) throws IOException {

        w.write("        if (this." + fieldName + " != null) {\n");
        w.write("            model.set" + capitalizedFieldName + "(\n");
        w.write("                this." + fieldName + ".entrySet().stream()\n");
        w.write("                    .collect(java.util.stream.Collectors.toMap(\n");
        w.write("                        e -> e.getKey(),\n");
        w.write("                        e -> e.getValue().toModel()\n");
        w.write("                    )));\n");
        w.write("        }\n");
    }

    private String getBaseType(TypeMirror type) {
        if (type instanceof DeclaredType) {
            DeclaredType declaredType = (DeclaredType) type;
            List<? extends TypeMirror> args = declaredType.getTypeArguments();

            // Collection ou Map
            if (!args.isEmpty()) {
                // Para List/Set → primeiro
                // Para Map → segundo (valor)
                if (isMap(type) && args.size() == 2) {
                    return args.get(1).toString();
                }
                return args.get(0).toString();
            }
        }

        return type.toString();
    }

    private boolean hasGenerateDTO(String fullTypeName) {
        TypeElement element = processingEnv
                .getElementUtils()
                .getTypeElement(fullTypeName);

        if (element == null) return false;

        return element.getAnnotation(GenerateDTO.class) != null;
    }

    private String getAutoDTOName(String fullTypeName) {
        return getSimpleTypeName(fullTypeName) + "DTO";
    }

    private String resolveNestedDTO(String fieldName,
                                    TypeMirror fieldType,
                                    Map<String, String> manualNested) {

        // 1. Prioridade para manual
        if (manualNested.containsKey(fieldName)) {
            return manualNested.get(fieldName);
        }

        // 2. Detectar automaticamente
        String baseType = getBaseType(fieldType);

        if (baseType.startsWith("java.lang")) return null;

        if (hasGenerateDTO(baseType)) {
            return getAutoDTOName(baseType);
        }

        return null;
    }

    private void addImport(Set<String> imports, String importName) {
        if (importName == null || importName.trim().isEmpty()) return;
        imports.add(importName);
    }

    private void collectImportsForType(TypeMirror type, Set<String> imports) {
        String typeStr = type.toString();

        if (typeStr.startsWith("java.util.List")) {
            addImport(imports, "java.util.List");
            addImport(imports, "java.util.stream.Collectors");
        }

        if (typeStr.startsWith("java.util.Set")) {
            addImport(imports, "java.util.Set");
            addImport(imports, "java.util.stream.Collectors");
        }

        if (typeStr.startsWith("java.util.Collection")) {
            addImport(imports, "java.util.Collection");
            addImport(imports, "java.util.stream.Collectors");
        }

        if (typeStr.startsWith("java.util.Map")) {
            addImport(imports, "java.util.Map");
            addImport(imports, "java.util.stream.Collectors");
        }
    }

    private void collectNestedImport(TypeMirror type, Set<String> imports) {
        if (!isNestedEntity(type)) return;

        String dtoName = getDtoName(type);
        String pkg = getPackage(type);

        if (pkg != null) {
            addImport(imports, pkg + "." + dtoName);
        }
    }

    private String getPackage(TypeMirror type) {
        String baseType = getBaseType(type);

        TypeElement element = processingEnv
                .getElementUtils()
                .getTypeElement(baseType);

        if (element == null) return null;

        return processingEnv
                .getElementUtils()
                .getPackageOf(element)
                .getQualifiedName()
                .toString();
    }

    private boolean isNestedEntity(TypeMirror type) {
        String baseType = getBaseType(type);

        if (baseType.startsWith("java.lang")) return false;
        if (baseType.startsWith("java.util")) return false;

        TypeElement element = processingEnv
                .getElementUtils()
                .getTypeElement(baseType);

        if (element == null) return false;

        return element.getAnnotation(GenerateDTO.class) != null;
    }

    private String getDtoName(TypeMirror type) {
        String baseType = getBaseType(type);
        return getSimpleTypeName(baseType) + "DTO";
    }

    private void processField(VariableElement field, Set<String> imports) {
        TypeMirror type = field.asType();

        collectImportsForType(type, imports);
        collectNestedImport(type, imports);
    }

    private void writeImports(Writer writer, Set<String> imports) throws IOException {
        if (imports == null || imports.isEmpty()) {
            writer.write("\n");
            return;
        }

        List<String> sorted = new ArrayList<>(imports);
        Collections.sort(sorted);

        for (String imp : sorted) {
            writer.write("import " + imp + ";\n");
        }

        writer.write("\n");
    }

    private Set<String> collectImports(TypeElement classElement,
                                       String[] selectedFields,
                                       Map<String, String> nestedDTOMap,
                                       String packageName) {

        Set<String> imports = new HashSet<>();

        for (Element enclosedElement : classElement.getEnclosedElements()) {
            if (enclosedElement.getKind() != ElementKind.FIELD) continue;

            VariableElement field = (VariableElement) enclosedElement;
            String fieldName = field.getSimpleName().toString();

            if (selectedFields.length > 0 && !contains(selectedFields, fieldName)) {
                continue;
            }

            // Processamento padrão
            processField(field, imports);

            // Nested DTO manual ou auto
            TypeMirror fieldType = field.asType();
            String dtoType = resolveNestedDTO(fieldName, fieldType, nestedDTOMap);
            if (dtoType != null) {
                imports.add(packageName + "." + dtoType);
            }
        }

        imports.add("java.util.Objects");

        return imports;
    }


}