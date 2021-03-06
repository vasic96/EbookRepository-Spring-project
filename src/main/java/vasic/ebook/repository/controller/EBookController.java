package vasic.ebook.repository.controller;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import vasic.ebook.repository.dto.EBookDTO;
import vasic.ebook.repository.entity.AppUser;
import vasic.ebook.repository.entity.Category;
import vasic.ebook.repository.entity.EBook;
import vasic.ebook.repository.entity.Language;
import vasic.ebook.repository.file.FileManager;
import vasic.ebook.repository.indexer.Indexer;
import vasic.ebook.repository.lucene.indexing.handlers.PDFHandler;
import vasic.ebook.repository.repository.AppUserRepo;
import vasic.ebook.repository.repository.EBookRepo;
import vasic.ebook.repository.service.CategoryService;
import vasic.ebook.repository.service.EBookService;
import vasic.ebook.repository.service.LanguageService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@RestController
public class EBookController {

	@Autowired
	private EBookService eBookService;
	
	@Autowired
	private CategoryService categoryService;
	
	@Autowired
	private LanguageService languageService;
	
	@Autowired
	private Indexer indexer;
	
	@Autowired
	private AppUserRepo userRepo;
	
	@Autowired
	private EBookRepo  ebookRepo;
	
	@Autowired
	private FileManager fileManager;
	
	
	@PreAuthorize("hasAuthority('admin')")
	@RequestMapping(value="/api/ebooks/upload",method=RequestMethod.POST,consumes = "multipart/form-data")
    public ResponseEntity<EBookDTO> checkbeforeUpload(@RequestParam("file") MultipartFile file) {


        if (file.getOriginalFilename().endsWith("pdf") && ebookExist(file.getOriginalFilename())) {
			File pdfFile = fileManager.saveToTemp(file);
			PDFHandler handler = new PDFHandler();
			EBookDTO ebookDTO = handler.getPdfMetaData(pdfFile, file.getOriginalFilename());

			return new ResponseEntity<EBookDTO>(ebookDTO, HttpStatus.OK);
		}		
		else {			
			return new ResponseEntity<>(HttpStatus.NOT_FOUND);
		}
	}

    private boolean ebookExist(String fileName) {

        return ebookRepo.findByFileName(fileName) == null;
    }
	
	
	@RequestMapping(value="open/ebooks",method=RequestMethod.GET)
	public ResponseEntity<List<EBookDTO>> getEbooks(){
		
		List<EBookDTO> dtos = new ArrayList<>();

		List<EBook> ebooks = eBookService.findAll();
		for (EBook eBook : ebooks) {
			System.out.println(eBook.getAuthor());
			dtos.add(new EBookDTO(eBook));	
		}	
			
		if(ebooks.isEmpty()) {
			
			return new ResponseEntity<>(HttpStatus.NOT_FOUND);
		}
		
		return new ResponseEntity<List<EBookDTO>>(dtos,HttpStatus.OK);

	}
	
	@RequestMapping(value="open/ebooks/{id}",method=RequestMethod.GET)
	public ResponseEntity<EBookDTO> getById(@PathVariable Integer id){
		
		EBook book = eBookService.findOne(id);
		if(book!=null) {
			
			return new ResponseEntity<EBookDTO>(new EBookDTO(book),HttpStatus.OK);
			
			
		}else {
			
			return new ResponseEntity<>(HttpStatus.NOT_FOUND);
			
		}
		
		
	}
	
	
	@RequestMapping(value="api/ebooks/dodaj",method=RequestMethod.POST,consumes="application/json")
	public ResponseEntity<EBookDTO> saveEbook(@RequestBody EBookDTO bookDTO) throws Exception{
		
		PDFHandler handler = new PDFHandler();

		//find language and category
		Category category = categoryService.findOne(bookDTO.getCategoryId());
		Language language = languageService.findOne(bookDTO.getLanguageId());

		String fileLocationTemp = new File("data").getAbsolutePath() + "\\" + bookDTO.getFileName();
		String fileLocation = new File("books").getAbsolutePath() + "\\" + bookDTO.getFileName();

		//read file from temp location
		File pdfFile = new File(fileLocationTemp);

		//set new attributes
		PDDocument doc = handler.setAttributes(pdfFile,bookDTO);

		//index pdf file with new attributes
		indexer.add(handler.getIndexUnitWithNewArgs(pdfFile, bookDTO));

		File f = new File(fileLocation);
		FileOutputStream fOut = new FileOutputStream(f);


		//decrypt file if encrypted
		if (doc.isEncrypted()) {
	        try {
	           
	            doc.setAllSecurityToBeRemoved(true);
	        }
	        catch (Exception e) {
	            throw new Exception("The document is encrypted, and we can't decrypt it.", e);
	        }
	    }
		
		doc.save(fOut);
		doc.close();
		
		//deleting temporary file
		pdfFile.delete();

		EBook book = new EBook();
		book.setTitle(bookDTO.getTitle());
		book.setAuthor(bookDTO.getAuthor());
		book.setPublicationYear(bookDTO.getPublicationYear());
		book.setKeywords(bookDTO.getKeywords());
		book.setFileName(bookDTO.getFileName());
		book.setCategory(category);
		book.setLanguage(language);
		
		return new ResponseEntity<EBookDTO>(new EBookDTO(eBookService.save(book)),HttpStatus.OK);
		
	}
	
	
	@RequestMapping(value="api/ebooks/download/{filePath}",method=RequestMethod.GET)
	public ResponseEntity<?> download(@PathVariable String filePath) throws IOException{
		
        String fileLocation1= new File("books").getAbsolutePath()+"\\"+filePath+".pdf";
        
        System.out.println(filePath);
        EBook ebook = ebookRepo.findByFileName(filePath + ".pdf");
        
        AppUser user = userRepo.findByUsername(SecurityContextHolder.getContext().getAuthentication().getPrincipal().toString());
        
		
		Path path = Paths.get(fileLocation1);
		if(new File(fileLocation1).exists() && (user.getRole().equalsIgnoreCase("admin") || user.getCategory().getId() == ebook.getCategory().getId())) {
			
			byte[] content = Files.readAllBytes(path);
			
			 HttpHeaders headers = new HttpHeaders();
			 headers.setContentType(MediaType.parseMediaType("application/pdf"));
			 headers.setContentDispositionFormData(filePath, filePath);
			 headers.setCacheControl("must-revalidate, post-check=0, pre-check=0");
			
			return new ResponseEntity<byte[]>(content,headers,HttpStatus.OK);
				
				
		}
		
		return new ResponseEntity<>(HttpStatus.NOT_FOUND);

		
	}
	
	
	@RequestMapping(value="open/ebooks/bycategory/{id}")
	public ResponseEntity<List<EBookDTO>> getEbookByCat(@PathVariable Integer id){
		
		
		List<EBookDTO> dtos = new ArrayList<EBookDTO>();
		List<EBook> ebooks = eBookService.findAllByCategoryId(id);
		
		for (EBook eBook : ebooks) {
			
			dtos.add(new EBookDTO(eBook));
			
		}
		
		return new ResponseEntity<List<EBookDTO>>(dtos,HttpStatus.OK);
		
		
		
	}
	
	@PreAuthorize("hasAuthority('admin')")
	@RequestMapping(value="api/ebooks/delete/{id}",method=RequestMethod.GET)
	public ResponseEntity<?> deleteEbook(@PathVariable Integer id){
		
		EBook eBook = eBookService.findOne(id);
		if(eBook != null) {
        
			String fileLocation1= new File("books").getAbsolutePath()+"\\"+eBook.getFileName();
			File pdfFile = new File(fileLocation1);
			indexer.delete(eBook.getFileName());
			pdfFile.delete();

			eBookService.remove(id);
			
			return new ResponseEntity<>(HttpStatus.OK);
			
		}else {
			
			return new ResponseEntity<>(HttpStatus.NOT_FOUND);
			
		}
	}
	
	@PreAuthorize("hasAuthority('admin')")
	@RequestMapping(value="api/ebooks/update/{id}")
	public ResponseEntity<EBookDTO> updateEbook(@RequestBody EBookDTO bookDTO,@PathVariable Integer id){
		
		PDFHandler handler = new PDFHandler();
		
		EBook ebook = eBookService.findOne(id);
		
		if(ebook != null) {
			
	        String fileLocation1= new File("books").getAbsolutePath()+"\\"+bookDTO.getFileName();
			File pdfFile = new File(fileLocation1);
			try {
			PDDocument doc = handler.setAttributes(pdfFile,bookDTO);

				indexer.update(handler.getIndexUnitWithNewArgs(pdfFile, bookDTO));
			
			File f = new File(fileLocation1);
			FileOutputStream fOut = new FileOutputStream(f);
			doc.save(fOut);
			doc.close();
			
			
			}catch (Exception e) {
				// TODO: handle exception
			}
			
			ebook.setTitle(bookDTO.getTitle());
			ebook.setAuthor(bookDTO.getAuthor());
			ebook.setKeywords(bookDTO.getKeywords());
			ebook.setPublicationYear(bookDTO.getPublicationYear());			
			return new ResponseEntity<EBookDTO>(new EBookDTO(eBookService.save(ebook)),HttpStatus.OK);
			
		}else {
			
			return new ResponseEntity<>(HttpStatus.NOT_FOUND);

			
		}
		
	}
	
         	  
}
	

	
	


	
	
	
	

