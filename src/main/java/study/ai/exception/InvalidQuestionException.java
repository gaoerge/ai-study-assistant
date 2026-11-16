package study.ai.exception;

/** 已知的问题输入错误；区别于配置错误和内部编程错误。 */
public class InvalidQuestionException extends RuntimeException {
    public InvalidQuestionException(String message) {
        super(message);
    }
}
