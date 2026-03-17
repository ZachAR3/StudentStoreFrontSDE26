package com.studentstorefront

import org.springframework.data.jpa.repository.JpaRepository


//This repo provides these methods automatically, just use them where needed:
// findAll() = returns all sellers
// findById(id) = returns a seller by ID
// save(seller) = inserts or updates a seller
// delete(seller) = deletes a seller
// existsById(id) = checks if a seller with the given ID exists
// Just autowire this interface in your service/controller with @Autowired or constructor injection

interface SellerRepository: JpaRepository<Seller, Long>{
}