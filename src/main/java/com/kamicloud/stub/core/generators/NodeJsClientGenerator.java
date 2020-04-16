package com.kamicloud.stub.core.generators;

import com.kamicloud.stub.core.stubs.OutputStub;
import com.kamicloud.stub.core.stubs.TemplateStub;
import com.kamicloud.stub.core.generators.components.common.FileCombiner;
import com.kamicloud.stub.core.generators.components.common.MultiLinesCombiner;
import org.thymeleaf.context.Context;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

public class NodeJsClientGenerator extends BaseGenerator {

    protected File outputDir;

    @Override
    public void postConstruct() {
        outputDir = new File(config.getGenerators().getNodejsClient().getOutput());

        outputDir.mkdirs();
    }

    @Override
    public void render(OutputStub o) {
        o.getTemplates().forEach(this::writeTemplate);

        writeTemplate("", o.getCurrentTemplate());
    }

    void writeTemplate(String version, TemplateStub templateStub) {
        FileCombiner file = new FileCombiner();

        file.setFileName(outputDir.getAbsolutePath() + "/API" + version + ".js");

        Locale locale = Locale.forLanguageTag("cn-zh");
        Context context = new Context(locale);
        context.setVariable("template", templateStub);
        String content = springTemplateEngine.process("js/api", context);

        file.addBlock(new MultiLinesCombiner(content));

        try {
            file.toFile();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}