package pt.tecnico.sauron.silo.exceptions;

public class InvalidIdException extends RuntimeException {
    public InvalidIdException() {
        super(ErrorMessage.NULL_ID.label);
    }

    public InvalidIdException(String type) {
        super(String.format(ErrorMessage.INVALID_ID.label, type));
    }
}
