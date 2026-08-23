package com.bookstore.bookstore_api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/books")
@CrossOrigin(origins = "*")
public class BookController {

    @Autowired
    private BookRepository bookRepository;

    // GET /books → return all books
    @GetMapping
    public List<Book> getAllBooks() {
        return bookRepository.findAll();
    }

    // GET /books/1 → return one book by id
    @GetMapping("/{id}")
    public Book getBookById(@PathVariable int id) {
        return bookRepository.findById(id).orElse(null);
    }

    // POST /books → add a new book
    @PostMapping
    public Book addBook(@RequestBody Book book) {
        return bookRepository.save(book);
    }

    // DELETE /books/1 → delete a book
    @DeleteMapping("/{id}")
    public String deleteBook(@PathVariable int id) {
        bookRepository.deleteById(id);
        return "Book " + id + " deleted successfully! - v2 🚀";
    }
}