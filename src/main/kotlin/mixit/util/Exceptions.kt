package mixit.util

class DuplicateException(message: String) : RuntimeException(message)
class TokenException(message: String) : RuntimeException(message)
class NotFoundException : RuntimeException()
class EmailSenderException(message: String) : RuntimeException(message)
class EmailValidatorException : RuntimeException()
class CredentialValidatorException : RuntimeException()
