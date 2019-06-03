package com.kamicloud.generator.writers;

import com.google.common.base.CaseFormat;
import com.kamicloud.generator.interfaces.PHPNamespacePathTransformerInterface;
import com.kamicloud.generator.stubs.*;
import com.kamicloud.generator.utils.FileUtil;
import com.kamicloud.generator.writers.components.php.*;
import definitions.annotations.*;
import definitions.annotations.Optional;
import definitions.types.Type;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class LaravelWriter extends BaseWriter implements PHPNamespacePathTransformerInterface {
    private File outputDir;
    private File generatedDir;
    private File routePath;

    private String boFolder;
    private String dtoFolder;
    private String dtoSuffix;
    private String serviceSuffix;
    private String serviceFolder;

    @Override
    void postConstruct() {
        boFolder = env.getProperty("generator.writers.laravel.bo-folder", "BOs");
        dtoFolder = env.getProperty("generator.writers.laravel.dto-folder", "DTOs");
        dtoSuffix = env.getProperty("generator.writers.laravel.dto-suffix", "DTO");
        serviceFolder = env.getProperty("generator.writers.laravel.service-folder", "Services");
        serviceSuffix = env.getProperty("generator.writers.laravel.service-suffix", "Service");

        String laravelPath = Objects.requireNonNull(env.getProperty("generator.writers.laravel.path"));
        outputDir = new File(laravelPath);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        generatedDir = new File(outputDir.getAbsolutePath() + "/app/Generated");
        routePath = new File(outputDir.getAbsolutePath() + "/routes/generated_routes.php");
        FileUtil.deleteAllFilesOfDir(generatedDir);
    }

    @Override
    public void update(OutputStub output) {
        output.getTemplates().forEach((version, templateStub) -> {
            try {
                ClassCombiner.setNamespacePathTransformer(this);
                writeHttp(version, templateStub);
                writeModels(version, templateStub);
                writeEnums(version, templateStub.getEnums());
            } catch (Exception e) {
                e.printStackTrace();
            }

        });
        try {
            writeErrors(output);
            writeRoute(output);

            writeEnums(boFolder, output.getCurrentTemplate().getEnums().stream().filter(enumStub -> {
                return enumStub.hasAnnotation(AsBO.class);
            }).collect(Collectors.toList()));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void writeModels(String version, TemplateStub templateStub) {
        templateStub.getModels().forEach((modelName, modelStub) -> {
            if (
                modelStub.hasAnnotation(AsBO.class) &&
                    templateStub.isCurrent()
            ) {
                writeModel(boFolder, modelStub);
            }
            writeModel(version, modelStub);
        });
    }

    private void writeModel(String version, ModelStub modelStub) {
        try {
            String modelName = modelStub.getName();
            ClassCombiner modelClassCombiner = new ClassCombiner(
                "App\\Generated\\" + version + "\\" + dtoFolder + "\\" + modelName + dtoSuffix,
                "Kamicloud\\StubApi\\DTOs\\DTO"
            );

            modelClassCombiner.addTrait("Kamicloud\\StubApi\\Concerns\\ValueHelper");

            HashMap<String, ParameterStub> parameters = modelStub.getParameters();

            writeParameterAttributes(parameters, modelClassCombiner);
            writeParameterGetters(parameters, modelClassCombiner);
            writeParameterSetters(parameters, modelClassCombiner);
            writeGetAttributeMapMethod(version, "getAttributeMap", parameters, modelClassCombiner);

            modelClassCombiner.toFile();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * HTTP层模板生成
     * <p>
     * 1、不使用
     *
     * @param o 模板解析文件
     */
    private void writeHttp(String version, TemplateStub o) {
        o.getControllers().forEach(controllerStub -> {
            try {
                String serviceClassName = "App\\Http\\" + serviceFolder + "\\" + version + "\\" + controllerStub.getName() + serviceSuffix;
                ClassCombiner controllerClassCombiner = new ClassCombiner(
                    "App\\Generated\\Controllers\\" + version + "\\" + controllerStub.getName() + "Controller",
                    "App\\Http\\Controllers\\Controller"
                );

                new ClassAttributeCombiner(controllerClassCombiner, "handler", "public");

                ClassMethodCombiner constructor = ClassMethodCombiner.build(
                    controllerClassCombiner,
                    "__construct",
                    "public"
                ).setBody(
                    "$this->handler = $handler;"
                );

                new ClassMethodParameterCombiner(constructor, "handler", serviceClassName);

                controllerStub.getActions().forEach((actionName, action) -> {
                    try {
                        String messageClassName = "App\\Generated\\" + version + "\\Messages\\" + controllerStub.getName() + "\\" + actionName + "Message";

                        ClassCombiner messageClassCombiner = new ClassCombiner(
                            messageClassName,
                            "Kamicloud\\StubApi\\Http\\Messages\\Message"
                        );

                        String lowerCamelActionName = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_CAMEL, action.getName());

                        controllerClassCombiner.addUse(serviceClassName);
                        controllerClassCombiner.addUse("DB");

                        ClassMethodCombiner actionClassMethodCombiner = new ClassMethodCombiner(controllerClassCombiner, lowerCamelActionName);
                        new ClassMethodParameterCombiner(actionClassMethodCombiner, "message", messageClassName);

                        String getResponseMethod = "getResponse";
                        if (action.hasAnnotation(FileResponse.class)) {
                            getResponseMethod = "getFileResponse";
                        }

                        actionClassMethodCombiner.setBody(
                            "$message->validateInput();",
                            "$this->handler->" + lowerCamelActionName + "($message);",
                            "$message->validateOutput();",
                            "return $message->" + getResponseMethod + "();"
                        );
                        if (action.hasAnnotation(Transactional.class)) {
                            actionClassMethodCombiner.wrapBody(
                                "return DB::transaction(function () use ($request) {",
                                "});"
                            );
                        }

                        messageClassCombiner.addTrait("Kamicloud\\StubApi\\Concerns\\ValueHelper");
                        // message
                        writeParameterGetters(action.getRequests(), messageClassCombiner);
                        writeParameterAttributes(action.getRequests(), messageClassCombiner);
                        writeParameterAttributes(action.getResponses(), messageClassCombiner);
                        writeGetAttributeMapMethod(version, "requestRules", action.getRequests(), messageClassCombiner);
                        writeGetAttributeMapMethod(version, "responseRules", action.getResponses(), messageClassCombiner);
                        if (action.hasAnnotation(FileResponse.class)) {
                            ClassMethodCombiner setResponseMethod = new ClassMethodCombiner(messageClassCombiner, "setFileResponse");
                            new ClassMethodParameterCombiner(setResponseMethod, "fileResponse");
                            setResponseMethod.addBody("$this->fileResponse = $fileResponse;");
                        } else {
                            ClassMethodCombiner setResponseMethod = new ClassMethodCombiner(messageClassCombiner, "setResponse");
                            writeMethodParameters(action.getResponses(), setResponseMethod);
                        }

                        messageClassCombiner.toFile();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
                controllerClassCombiner.toFile();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void writeEnums(String version, List<EnumStub> enumStubs) {
        enumStubs.forEach(enumStub -> {
            try {
                ClassCombiner enumClassCombiner = new ClassCombiner(
                    "App\\Generated\\" + version + "\\Enums\\" + enumStub.getName(),
                    "Kamicloud\\StubApi\\BOs\\Enum"
                );

                ClassConstantCombiner mapConstant = new ClassConstantCombiner(
                    "_MAP",
                    EnumStub.EnumStubItemType.EXPRESSION,
                    "public"
                );
                mapConstant.addLine("[");
                enumStub.getItems().forEach((key, value) -> {
                    String valueName = value.getName();
                    EnumStub.EnumStubItemType valueType = value.getType();

                    if (enumStub.hasAnnotation(StringEnum.class)) {
                        valueType = EnumStub.EnumStubItemType.STRING;
                        valueName = key;
                    }
                    ClassConstantCombiner enumClassConstantCombiner = new ClassConstantCombiner(key, valueType);
                    enumClassConstantCombiner.addLine(valueName);
                    enumClassCombiner.addConstant(enumClassConstantCombiner);

                    mapConstant.addLine("    self::" + key + " => '" + key + "',");
                });
                mapConstant.addLine("]");
                enumClassCombiner.addConstant(mapConstant);

                enumClassCombiner.toFile();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void writeErrors(OutputStub o) throws Exception {
        ClassCombiner errorCodeClassCombiner = new ClassCombiner("App\\Generated\\Exceptions\\ErrorCode");
        o.getErrors().forEach(error -> {
            try {
                // error code
                ClassConstantCombiner constant = new ClassConstantCombiner(error.getName(), null);
                constant.addLine(error.getCode());
                errorCodeClassCombiner.addConstant(constant);
                String exceptionName = CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, error.getName());
                ClassCombiner exceptionClassCombiner = new ClassCombiner(
                    "App\\Generated\\Exceptions\\" + exceptionName + "Exception",
                    "Kamicloud\\StubApi\\Exceptions\\BaseException"
                );

                ClassMethodCombiner constructMethodCombiner = new ClassMethodCombiner(exceptionClassCombiner, "__construct");
                new ClassMethodParameterCombiner(constructMethodCombiner, "message", null, "null");
                constructMethodCombiner.addBody("parent::__construct($message, ErrorCode::" + error.getName() + ");");

                exceptionClassCombiner.toFile();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        errorCodeClassCombiner.toFile();
    }

    private void writeRoute(OutputStub o) throws IOException {
        PHPFileCombiner fileCombiner = new PHPFileCombiner();

        fileCombiner.setFileName(routePath.getAbsolutePath());

        o.getTemplates().forEach((version, templateStub) -> {
            templateStub.getControllers().forEach(controller -> controller.getActions().forEach((actionName, action) -> {
                AnnotationStub methodsAnnotation = action.getAnnotation(Methods.class);
                ArrayList<String> allowMethods;
                String method;
                if (methodsAnnotation != null) {
                    allowMethods = methodsAnnotation.getValues();
                    method = "['" + String.join("', '", allowMethods) + "', 'POST']";
                } else {
                    method = "['POST']";
                }
                String middlewarePart = "";
                if (action.hasAnnotation(Middleware.class)) {
                    AnnotationStub x = action.getAnnotation(Middleware.class);
                    middlewarePart = "->middleware(['" + String.join("', '", x.getValues()) + "'])";
                }
                fileCombiner.addLine(
                    "Route::match(" + method + ", '" + action.getUri() + "', '" + version + "\\" + controller.getName() + "Controller@" + actionName + "')" + middlewarePart + ";"
                );
            }));
        });

        fileCombiner.toFile();
    }

    @Override
    public String namespaceToPath(String namespace) {
        return outputDir.getAbsolutePath() + "/" + namespace.replace("App\\", "app/").replace("\\", "/") + ".php";
    }

    @Override
    public String pathToNamespace(String path) {
        return null;
    }

    private void writeParameterAttributes(HashMap<String, ParameterStub> parameters, ClassCombiner classCombiner) {
        parameters.forEach((parameterName, parameterStub) -> new ClassAttributeCombiner(classCombiner, parameterStub.getName(), "protected"));
    }

    private void writeParameterGetters(HashMap<String, ParameterStub> parameters, ClassCombiner classCombiner) {
        parameters.forEach((parameterName, parameterStub) -> {
            writeParameterGetter(
                parameterStub,
                classCombiner,
                "get"
            );

            if (parameterStub.isBoolean()) {
                writeParameterGetter(
                    parameterStub,
                    classCombiner,
                    "is"
                );
            }
        });
    }

    private void writeParameterGetter(ParameterStub parameterStub, ClassCombiner classCombiner, String prefix) {
        String parameterName = CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, parameterStub.getName());

        parameterName = prefix + parameterName;

        ClassMethodCombiner classMethodCombiner = new ClassMethodCombiner(classCombiner, parameterName);
        classMethodCombiner.setBody("return $this->" + parameterStub.getName() + ";");

        String comment = parameterStub.getComment();

        classMethodCombiner.addComment(parameterStub.getComment());
    }

    private void writeParameterSetters(HashMap<String, ParameterStub> parameters, ClassCombiner classCombiner) {
        parameters.forEach((parameterName, parameterStub) -> writeParameterSetter(parameterStub, classCombiner));
    }

    private void writeParameterSetter(ParameterStub parameterStub, ClassCombiner classCombiner) {
        String parameterName = CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, parameterStub.getName());
        ClassMethodCombiner classMethodCombiner = new ClassMethodCombiner(classCombiner, "set" + parameterName);
        classMethodCombiner.addBody("$this->" + parameterStub.getName() + " = $" + parameterStub.getName() + ";");
        new ClassMethodParameterCombiner(classMethodCombiner, parameterStub.getName());
    }

    private void writeMethodParameters(HashMap<String, ParameterStub> parameters, ClassMethodCombiner classMethodCombiner) {
        parameters.forEach((parameterName, parameterStub) -> writeMethodParameter(parameterStub, classMethodCombiner));
    }

    private void writeMethodParameter(ParameterStub parameterStub, ClassMethodCombiner classMethodCombiner) {
        new ClassMethodParameterCombiner(classMethodCombiner, parameterStub.getName());

        classMethodCombiner.addBody("$this->" + parameterStub.getName() + " = $" + parameterStub.getName() + ";");
    }

    /**
     * @param parameters    参数
     * @param classCombiner 目标类
     */
    private void writeGetAttributeMapMethod(String version, String methodName, HashMap<String, ParameterStub> parameters, ClassCombiner classCombiner) {
        classCombiner.addUse("Kamicloud\\StubApi\\Utils\\Constants");
        ClassMethodCombiner classMethodCombiner = new ClassMethodCombiner(classCombiner, methodName);

        parameters.forEach((parameterName, parameterStub) -> {
            String typeName = parameterStub.getTypeSimpleName();
            String rule;
            ArrayList<String> ruleList = new ArrayList<String>() {{
                add("bail");
            }};
            ArrayList<String> types = new ArrayList<>();
            boolean isArray = parameterStub.isArray();
            boolean isModel = parameterStub.isModel();
            boolean isEnum = parameterStub.isEnum();
            if (parameterStub.hasAnnotation(Optional.class)) {
                types.add("Constants::OPTIONAL");
                ruleList.add("nullable");
            }
            if (parameterStub.hasAnnotation(Mutable.class)) {
                types.add("Constants::MUTABLE");
            }
            if (isArray) {
                types.add("Constants::ARRAY");
            }
            if (isModel) {
                classCombiner.addUse("App\\Generated\\" + version + "\\" + dtoFolder + "\\" + typeName + dtoSuffix);
                rule = typeName + dtoSuffix + "::class";
                types.add("Constants::MODEL");
            } else if (isEnum) {
                classCombiner.addUse("App\\Generated\\" + version + "\\Enums\\" + typeName);
                rule = typeName + "::class";
                types.add("Constants::ENUM");
            } else {
                Type typeInstance = parameterStub.getType();

                ruleList.add(typeInstance.getLaravelRule());
                rule = "'" + String.join("|", ruleList) + "'";
            }

            String dbField = isModel ? parameterName :
                stringUtil.lowerCamelToLowerUnderscore(parameterName);

            AnnotationStub annotationStub = parameterStub.getAnnotation(DBField.class);

            /* 如果参数有指定的映射关系，使用指定的映射 */
            if (annotationStub != null) {
                String fieldValue = annotationStub.getValue();
                if (!fieldValue.equals("")) {
                    dbField = fieldValue;
                }
            }

            String laravelParam = parameterStub.getType().getLaravelParam();

            ArrayList<String> params = new ArrayList<>(Arrays.asList(
                "'" + parameterName + "'",
                "'" + dbField + "'",
                rule,
                types.isEmpty() ? "null" : String.join(" | ", types),
                laravelParam == null ? "null" : ("'" + laravelParam + "'")
            ));
            classMethodCombiner.addBody("[" + String.join(", ", params) + "],");
        });
        classMethodCombiner.wrapBody("return [", "];");
    }
}
