package cn.bank.devtool;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.printer.configuration.PrettyPrinterConfiguration;
import java.util.ArrayList;

public final class GeneratedJavaStyle {
    private GeneratedJavaStyle() {
    }

    public static String format(String source) {
        CompilationUnit unit = StaticJavaParser.parse(source);

        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            String existing = type.getJavadocComment().map(c -> c.getContent()).orElse("");
            if (!hasChinese(existing) || boilerplate(existing)) {
                type.setJavadocComment(describeType(type, unit) + "\n" + removeBoilerplate(existing));
            }

            for (BodyDeclaration<?> member : new ArrayList<>(type.getMembers())) {
                if (member instanceof FieldDeclaration && ((FieldDeclaration)member).getVariables().size() > 1) {
                    FieldDeclaration field = (FieldDeclaration)member;
                    int index = type.getMembers().indexOf(member);
                    type.getMembers().remove(index);

                    for (VariableDeclarator variable : field.getVariables()) {
                        FieldDeclaration single = field.clone();
                        single.setVariables(new NodeList(new VariableDeclarator[]{variable.clone()}));
                        type.getMembers().add(index++, single);
                    }
                }
            }
        }

        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            String existing = method.getJavadocComment().map(c -> c.getContent()).orElse("");
            // Field documentation already describes a plain property. Preserve authored
            // accessors and setters with side effects, but do not multiply boilerplate.
            if (existing.trim().isEmpty() && plainAccessor(method)) {
                continue;
            }
            if ((
                    method.isPublic()
                        || method.getParentNode()
                            .filter(n -> n instanceof ClassOrInterfaceDeclaration && ((ClassOrInterfaceDeclaration)n).isInterface())
                            .isPresent()
                )
                && (!hasChinese(existing) || boilerplate(existing))) {
                method.setJavadocComment(describe(method) + "\n" + removeBoilerplate(existing));
            }
        }

        for (IfStmt statement : unit.findAll(IfStmt.class)) {
            if (!(statement.getThenStmt() instanceof BlockStmt)) {
                statement.setThenStmt(block(statement.getThenStmt()));
            }

            statement.getElseStmt().ifPresent(body -> {
                if (!(body instanceof BlockStmt) && !(body instanceof IfStmt)) {
                    statement.setElseStmt(block(body));
                }
            });
        }

        for (ForStmt statement : unit.findAll(ForStmt.class)) {
            if (!(statement.getBody() instanceof BlockStmt)) {
                statement.setBody(block(statement.getBody()));
            }
        }

        for (ForEachStmt statementx : unit.findAll(ForEachStmt.class)) {
            if (!(statementx.getBody() instanceof BlockStmt)) {
                statementx.setBody(block(statementx.getBody()));
            }
        }

        for (WhileStmt statementxx : unit.findAll(WhileStmt.class)) {
            if (!(statementxx.getBody() instanceof BlockStmt)) {
                statementxx.setBody(block(statementxx.getBody()));
            }
        }

        PrettyPrinterConfiguration configuration = new PrettyPrinterConfiguration()
            .setIndentSize(4)
            .setEndOfLineCharacter("\n")
            .setSpaceAroundOperators(true)
            .setOrderImports(true);
        return unit.toString(configuration).trim() + "\n";
    }

    private static BlockStmt block(Statement body) {
        return new BlockStmt(new NodeList(new Statement[]{body.clone()}));
    }

    private static boolean hasChinese(String text) {
        return text.matches("(?s).*[\\u4e00-\\u9fff].*");
    }

    private static boolean plainAccessor(MethodDeclaration method) {
        String body = method.getBody().map(b -> b.toString().replaceAll("\\s+", "")).orElse("");
        String name = method.getNameAsString();
        if (method.isStatic() || !(method.getParentNode().orElse(null) instanceof ClassOrInterfaceDeclaration)) {
            return false;
        }
        ClassOrInterfaceDeclaration owner = (ClassOrInterfaceDeclaration) method.getParentNode().get();
        for (FieldDeclaration declaration : owner.getFields()) {
            for (VariableDeclarator field : declaration.getVariables()) {
                String property = field.getNameAsString();
                String suffix = Character.toUpperCase(property.charAt(0)) + property.substring(1);
                if (method.getParameters().isEmpty() && (name.equals("get" + suffix) || name.equals("is" + suffix))
                    && (body.equals("{return" + property + ";}") || body.equals("{returnthis." + property + ";}"))) {
                    return true;
                }
                if (name.equals("set" + suffix) && method.getParameters().size() == 1 && method.getType().isVoidType()) {
                    String value = method.getParameter(0).getNameAsString();
                    if (body.equals("{this." + property + "=" + value + ";}")
                        || !property.equals(value) && body.equals("{" + property + "=" + value + ";}")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean boilerplate(String text) {
        return text.contains("使用方式与约束见所属组件说明") || text.contains("职责和接入要求见生成工程 README");
    }

    private static String removeBoilerplate(String text) {
        return text.replaceAll("生成的 [^\\r\\n]*? 组件。职责和接入要求见生成工程 README。", "")
            .replaceAll("执行 [^\\r\\n]*? 操作，使用方式与约束见所属组件说明。", "")
            .replace("职责和接入要求见生成工程 README。", "")
            .replace("使用方式与约束见所属组件说明。", "").trim();
    }

    private static String describeType(TypeDeclaration<?> type, CompilationUnit unit) {
        String name = type.getNameAsString();
        String pkg = unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        if (pkg.endsWith(".dto")) return name + " 请求数据结构；由请求绑定器构造，在服务代理入口执行字段约束校验，不能直接作为数据库实体使用。";
        if (pkg.endsWith(".vo")) return name + " 响应视图；仅承载规格中允许对外展示的字段，由服务显式映射后交给 JSON 序列化器。";
        if (pkg.endsWith(".entity")) return name + " 数据库映射对象；供 Mapper 读写持久化字段，不直接接收客户端请求或作为接口响应。";
        if (type.isAnnotationDeclaration()) return name + " 校验注解；用于声明字段或对象约束，由 Bean Validation 在受管服务入口执行。";
        return name + " 组件，位于 " + pkg + " 包；公开入口的参数与返回类型见各方法说明。";
    }

    private static String describe(MethodDeclaration method) {
        String name = method.getNameAsString();
        StringBuilder text = new StringBuilder();
        // Infer accessors from the actual syntax, not only the method name: getOffset, for
        // example, performs a calculation and must retain its explicit template documentation.
        String body = method.getBody().map(b -> b.toString().replaceAll("\\s+", "")).orElse("");
        if (method.getParameters().isEmpty() && body.matches("\\{return(this\\.)?[A-Za-z_$][A-Za-z0-9_$]*;\\}")) {
            String field = body.substring(7, body.length() - 2).replace("this.", "");
            text.append("读取当前对象的 ").append(field).append(" 属性；直接返回已有值，不查询数据库。");
        } else if (method.getParameters().size() == 1 && body.matches("\\{(this\\.)?[A-Za-z_$][A-Za-z0-9_$]*=[A-Za-z_$][A-Za-z0-9_$]*;\\}")) {
            text.append("将参数赋给当前对象属性；此方法仅赋值，不执行校验或持久化，调用方应通过受管服务触发约束校验。");
        } else if (method.getAnnotationByName("ExceptionHandler").isPresent()) {
            text.append("由 Spring MVC 在匹配的异常发生后调用，将异常转换为本方法声明的 HTTP 响应；业务代码无需直接调用。");
        } else if (name.equals("isValid") && method.getAnnotationByName("Override").isPresent()) {
            text.append("由 Bean Validation 调用以检查当前约束；返回 false 表示约束不满足，null 是否通过由本校验器实现决定。");
        } else if (name.equals("initialize") && method.getAnnotationByName("Override").isPresent()) {
            text.append("由 Bean Validation 在校验前传入注解实例，初始化该约束的校验参数。");
        } else if (name.equals("deserialize") && method.getAnnotationByName("Override").isPresent()) {
            text.append("由 Jackson 解析 JSON 时调用，将输入转换为声明的 Java 类型；不合法的格式通过异常中止反序列化。");
        } else if (name.equals("serialize") && method.getAnnotationByName("Override").isPresent()) {
            text.append("由 Jackson 输出 JSON 时调用，按该序列化器的传输格式写入值。");
        } else {
            String owner = method.findAncestor(TypeDeclaration.class).map(t -> t.getNameAsString()).orElse("当前组件");
            text.append("调用 ").append(owner).append('.').append(name).append("(");
            for (int i = 0; i < method.getParameters().size(); i++) {
                if (i > 0) text.append(", ");
                Parameter parameter = method.getParameter(i);
                text.append(parameter.getTypeAsString()).append(' ').append(parameter.getNameAsString());
            }
            text.append(")。");
            if (method.isAbstract() || !method.getBody().isPresent()) text.append("此入口由实现类提供行为，调用方通过注入的组件使用。");
        }
        for (Parameter parameter : method.getParameters()) {
            text.append("\n@param ").append(parameter.getNameAsString()).append(" 输入参数，类型为 ").append(parameter.getTypeAsString());
            if (!parameter.getAnnotations().isEmpty()) text.append("；须满足声明的参数注解约束");
        }
        if (!method.getType().isVoidType()) text.append("\n@return ").append(method.getTypeAsString()).append(" 类型的处理结果");
        method.getThrownExceptions().forEach(t -> text.append("\n@throws ").append(t.asString()).append(" 本方法声明的异常，调用方必须处理或继续传播"));
        return text.toString();
    }
}
